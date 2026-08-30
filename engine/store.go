package main

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

type sessionStore struct {
	mu       sync.Mutex
	path     string
	sessions map[string]*session
}

func openSessionStore(dataDir string) (*sessionStore, error) {
	dir := filepath.Join(dataDir, "sessions")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	s := &sessionStore{path: filepath.Join(dir, "sessions.json"), sessions: map[string]*session{}}
	raw, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	if len(raw) > 0 && json.Unmarshal(raw, &s.sessions) != nil {
		return nil, errors.New("会话数据已损坏")
	}
	return s, nil
}

func (s *sessionStore) saveLocked() error {
	raw, err := json.Marshal(s.sessions)
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func cloneSession(value *session) *session {
	raw, _ := json.Marshal(value)
	var cloned session
	_ = json.Unmarshal(raw, &cloned)
	return &cloned
}

func (s *sessionStore) get(id string) (*session, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	value, ok := s.sessions[id]
	if !ok {
		return nil, false
	}
	return cloneSession(value), true
}

func (s *sessionStore) list(query string, starred bool) []sessionSummary {
	s.mu.Lock()
	defer s.mu.Unlock()
	query = strings.ToLower(strings.TrimSpace(query))
	result := make([]sessionSummary, 0, len(s.sessions))
	for _, value := range s.sessions {
		if starred && !value.Starred || query != "" && !strings.Contains(strings.ToLower(value.Title), query) {
			continue
		}
		result = append(result, sessionSummary{value.ID, value.Title, value.UpdatedAt, value.Model, value.Starred})
	}
	sort.Slice(result, func(i, j int) bool { return result[i].UpdatedAt > result[j].UpdatedAt })
	return result
}

func (s *sessionStore) create(value *session) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sessions[value.ID] = cloneSession(value)
	return s.saveLocked()
}

func (s *sessionStore) addUser(id string, e event, t turn) (*session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	value := s.sessions[id]
	if value == nil {
		return nil, os.ErrNotExist
	}
	value.Events = append(value.Events, e)
	value.Turns = append(value.Turns, t)
	value.UpdatedAt = e.Timestamp
	if err := s.saveLocked(); err != nil {
		return nil, err
	}
	return cloneSession(value), nil
}

func (s *sessionStore) finish(id string, e event, output []json.RawMessage) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	value := s.sessions[id]
	if value == nil {
		return os.ErrNotExist
	}
	value.Events = append(value.Events, e)
	if len(value.Turns) > 0 {
		value.Turns[len(value.Turns)-1].Output = output
	}
	value.UpdatedAt = e.Timestamp
	return s.saveLocked()
}

func (s *sessionStore) patch(id string, title *string, starred *bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	value := s.sessions[id]
	if value == nil {
		return os.ErrNotExist
	}
	if title != nil && strings.TrimSpace(*title) != "" {
		value.Title = strings.TrimSpace(*title)
	}
	if starred != nil {
		value.Starred = *starred
	}
	value.UpdatedAt = nowText()
	return s.saveLocked()
}

func (s *sessionStore) delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.sessions[id]; !ok {
		return os.ErrNotExist
	}
	delete(s.sessions, id)
	return s.saveLocked()
}
