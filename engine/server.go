package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type activeRun struct {
	mu     sync.Mutex
	events []event
	done   bool
	err    string
	notify chan struct{}
	cancel context.CancelFunc
}

func newActiveRun(cancel context.CancelFunc) *activeRun {
	return &activeRun{notify: make(chan struct{}), cancel: cancel}
}

func (r *activeRun) append(value event) {
	r.mu.Lock()
	r.events = append(r.events, value)
	close(r.notify)
	r.notify = make(chan struct{})
	r.mu.Unlock()
}

func (r *activeRun) finish(message string) {
	r.mu.Lock()
	r.done, r.err = true, message
	close(r.notify)
	r.notify = make(chan struct{})
	r.mu.Unlock()
}

func (r *activeRun) snapshot(cursor int) ([]event, int, bool, string, <-chan struct{}) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if cursor < 0 || cursor > len(r.events) {
		cursor = 0
	}
	items := append([]event(nil), r.events[cursor:]...)
	return items, len(r.events), !r.done, r.err, r.notify
}

type engineServer struct {
	key        string
	store      *sessionStore
	provider   *deepSeekProvider
	runsMu     sync.Mutex
	runs       map[string]*activeRun
	compaction compactionConfig
}

func newEngineServer(cfg config) (*engineServer, error) {
	store, err := openSessionStore(cfg.DataDir)
	if err != nil {
		return nil, err
	}
	provider, err := newDeepSeekProvider(cfg.APIKey, cfg.BaseURL)
	if err != nil {
		return nil, err
	}
	location := time.Local
	if strings.TrimSpace(cfg.Timezone) != "" {
		location, err = time.LoadLocation(strings.TrimSpace(cfg.Timezone))
		if err != nil {
			return nil, fmt.Errorf("设备时区无效: %w", err)
		}
	}
	provider.configureLocalTools(location)
	if err := provider.configureWebFetch(cfg.ProxyURL); err != nil {
		return nil, err
	}
	if strings.TrimSpace(cfg.InternalAPIKey) == "" {
		return nil, errors.New("内部 API Key 不能为空")
	}
	return &engineServer{
		key: cfg.InternalAPIKey, store: store, provider: provider,
		runs: map[string]*activeRun{}, compaction: defaultCompactionConfig(),
	}, nil
}

func (s *engineServer) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/chat/models", s.models)
	mux.HandleFunc("GET /api/chat/sessions", s.listSessions)
	mux.HandleFunc("POST /api/chat/run_sse", s.runSSE)
	mux.HandleFunc("/api/chat/sessions/", s.sessionRoute)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		auth := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if subtle.ConstantTimeCompare([]byte(auth), []byte(s.key)) != 1 {
			writeError(w, http.StatusUnauthorized, "未授权")
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func (s *engineServer) models(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"defaultModel": "deepseek", "models": []map[string]string{{"name": "deepseek"}}})
}

func (s *engineServer) listSessions(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	offset, _ := strconv.Atoi(r.URL.Query().Get("cursor"))
	if offset < 0 {
		offset = 0
	}
	items := s.store.list(r.URL.Query().Get("q"), r.URL.Query().Get("starred") == "true")
	if offset > len(items) {
		offset = len(items)
	}
	end := offset + limit
	if end > len(items) {
		end = len(items)
	}
	next := ""
	if end < len(items) {
		next = strconv.Itoa(end)
	}
	writeJSON(w, http.StatusOK, map[string]any{"sessions": items[offset:end], "nextCursor": next, "hasMore": end < len(items)})
}

func (s *engineServer) sessionRoute(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/api/chat/sessions/")
	pieces := strings.Split(rest, "/")
	id := pieces[0]
	if id == "" || strings.Contains(id, "..") {
		writeError(w, http.StatusBadRequest, "会话 ID 无效")
		return
	}
	if len(pieces) == 2 {
		switch pieces[1] {
		case "run_events":
			if r.Method == http.MethodGet {
				s.runEvents(w, r, id)
				return
			}
		case "cancel":
			if r.Method == http.MethodPost {
				s.cancelRun(w, id)
				return
			}
		}
		writeError(w, http.StatusNotFound, "接口不存在")
		return
	}
	switch r.Method {
	case http.MethodGet:
		value, ok := s.store.get(id)
		if !ok {
			writeError(w, http.StatusNotFound, "会话不存在")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"session": sessionSummary{value.ID, value.Title, value.UpdatedAt, value.Model, value.Starred}, "events": visibleEvents(value.Events), "running": s.isRunning(id)})
	case http.MethodPatch:
		var patch struct {
			Title   *string `json:"title"`
			Starred *bool   `json:"starred"`
		}
		if !decodeBody(w, r, &patch) {
			return
		}
		if err := s.store.patch(id, patch.Title, patch.Starred); errors.Is(err, os.ErrNotExist) {
			writeError(w, http.StatusNotFound, "会话不存在")
			return
		} else if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		w.WriteHeader(http.StatusNoContent)
	case http.MethodDelete:
		if s.isRunning(id) {
			writeError(w, http.StatusConflict, "会话仍在生成中")
			return
		}
		if err := s.store.delete(id); errors.Is(err, os.ErrNotExist) {
			writeError(w, http.StatusNotFound, "会话不存在")
			return
		} else if err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		w.WriteHeader(http.StatusNoContent)
	default:
		writeError(w, http.StatusMethodNotAllowed, "方法不支持")
	}
}

func (s *engineServer) runSSE(w http.ResponseWriter, r *http.Request) {
	var input runRequest
	if !decodeBody(w, r, &input) {
		return
	}
	input.Message = strings.TrimSpace(input.Message)
	if input.Message == "" && len(input.Images) == 0 && len(input.Files) == 0 {
		writeError(w, http.StatusBadRequest, "消息不能为空")
		return
	}
	if len(input.Images) > 8 || len(input.Files) > 8 {
		writeError(w, http.StatusBadRequest, "附件数量过多")
		return
	}
	for _, a := range append(append([]attachment(nil), input.Images...), input.Files...) {
		if strings.TrimSpace(a.Name) == "" || strings.TrimSpace(a.MIMEType) == "" || (a.Data == "" && a.URL == "") {
			writeError(w, http.StatusBadRequest, "附件无效")
			return
		}
	}

	isNew := input.SessionID == ""
	if isNew {
		input.SessionID = randomID()
		title := input.Message
		if title == "" {
			title = "附件对话"
		}
		title = truncateRunes(title, 36)
		value := &session{ID: input.SessionID, Title: title, UpdatedAt: nowText(), Model: "deepseek", Events: []event{}, Turns: []turn{}}
		if err := s.store.create(value); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
	} else if _, ok := s.store.get(input.SessionID); !ok {
		writeError(w, http.StatusNotFound, "会话不存在")
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	run := newActiveRun(cancel)
	s.runsMu.Lock()
	if existing := s.runs[input.SessionID]; existing != nil {
		_, _, running, _, _ := existing.snapshot(0)
		if running {
			s.runsMu.Unlock()
			cancel()
			writeError(w, http.StatusConflict, "该会话正在生成")
			return
		}
	}
	s.runs[input.SessionID] = run
	s.runsMu.Unlock()

	userParts := []part{}
	if input.Message != "" {
		userParts = append(userParts, part{Type: "text", Text: input.Message})
	}
	for _, image := range input.Images {
		userParts = append(userParts, part{Type: "image", Name: image.Name, Data: image.Data, URL: image.URL, MIMEType: image.MIMEType})
	}
	for _, file := range input.Files {
		userParts = append(userParts, part{Type: "file", Name: file.Name, Data: file.Data, URL: file.URL, MIMEType: file.MIMEType})
	}
	userEvent := event{ID: randomID(), Author: "user", Timestamp: nowText(), Parts: userParts}
	value, err := s.store.addUser(input.SessionID, userEvent, turn{Message: input.Message, Images: input.Images, Files: input.Files})
	if err != nil {
		s.removeRun(input.SessionID)
		cancel()
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	run.append(userEvent)
	go s.generate(ctx, input.SessionID, value.Turns, input.Model, run)

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("X-Accel-Buffering", "no")
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeError(w, http.StatusInternalServerError, "不支持流式响应")
		return
	}
	writeSSE(w, "session", map[string]any{"sessionId": input.SessionID, "title": value.Title, "isNew": isNew, "model": "deepseek"})
	flusher.Flush()
	cursor := 0
	for {
		items, next, running, runError, notify := run.snapshot(cursor)
		for _, item := range items {
			writeSSE(w, "event", item)
		}
		cursor = next
		flusher.Flush()
		if !running {
			if runError != "" {
				writeSSE(w, "error", map[string]string{"message": runError})
			}
			writeSSE(w, "done", map[string]string{"sessionId": input.SessionID})
			flusher.Flush()
			return
		}
		select {
		case <-r.Context().Done():
			return
		case <-notify:
		}
	}
}

func (s *engineServer) generate(ctx context.Context, sessionID string, turns []turn, model string, run *activeRun) {
	prompt, compactErr := s.compactIfNeeded(ctx, sessionID)
	if compactErr != nil {
		fmt.Fprintf(os.Stderr, "context compaction failed: %v\n", compactErr)
	}
	if prompt != nil {
		turns = prompt
	}
	responseID := randomID()
	result, err := s.provider.stream(ctx, turns, model, sessionID, func(delta providerDelta) {
		run.append(event{ID: responseID, ResponseID: responseID, InvocationID: responseID, Author: "assistant", Partial: true, Timestamp: nowText(), Parts: []part{delta.Part}})
	})
	if err != nil {
		if errors.Is(err, context.Canceled) {
			run.finish("")
		} else {
			run.finish(err.Error())
		}
		return
	}
	parts := result.Parts
	if len(parts) == 0 {
		if result.Thinking != "" {
			parts = append(parts, part{Type: "thinking", Text: result.Thinking})
		}
		if result.Text != "" {
			parts = append(parts, part{Type: "text", Text: result.Text})
		}
	}
	final := event{ID: responseID, ResponseID: responseID, InvocationID: responseID, Author: "assistant", Timestamp: nowText(), Parts: parts, Usage: &result.Usage}
	if err := s.store.finish(sessionID, final, result.Output); err != nil {
		run.finish(err.Error())
		return
	}
	run.append(final)
	run.finish("")
}

func (s *engineServer) runEvents(w http.ResponseWriter, r *http.Request, id string) {
	cursor, _ := strconv.Atoi(r.URL.Query().Get("cursor"))
	s.runsMu.Lock()
	run := s.runs[id]
	s.runsMu.Unlock()
	if run == nil {
		writeJSON(w, http.StatusOK, map[string]any{"events": []event{}, "nextCursor": cursor, "running": false})
		return
	}
	items, next, running, _, _ := run.snapshot(cursor)
	writeJSON(w, http.StatusOK, map[string]any{"events": items, "nextCursor": next, "running": running})
}

func (s *engineServer) cancelRun(w http.ResponseWriter, id string) {
	s.runsMu.Lock()
	run := s.runs[id]
	s.runsMu.Unlock()
	if run != nil {
		run.cancel()
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *engineServer) isRunning(id string) bool {
	s.runsMu.Lock()
	run := s.runs[id]
	s.runsMu.Unlock()
	if run == nil {
		return false
	}
	_, _, running, _, _ := run.snapshot(0)
	return running
}
func (s *engineServer) removeRun(id string) { s.runsMu.Lock(); delete(s.runs, id); s.runsMu.Unlock() }

func randomID() string {
	var value [16]byte
	_, _ = rand.Read(value[:])
	return hex.EncodeToString(value[:])
}
func truncateRunes(value string, count int) string {
	if utf8.RuneCountInString(value) <= count {
		return value
	}
	return string([]rune(value)[:count]) + "…"
}
func decodeBody(w http.ResponseWriter, r *http.Request, target any) bool {
	defer r.Body.Close()
	err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32<<20)).Decode(target)
	if err != nil {
		writeError(w, http.StatusBadRequest, "请求内容无效")
		return false
	}
	return true
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}
func writeSSE(w http.ResponseWriter, name string, value any) {
	raw, _ := json.Marshal(value)
	_, _ = fmt.Fprintf(w, "event: %s\ndata: %s\n\n", name, raw)
}
