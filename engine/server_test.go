package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
)

func TestSessionStoreRoundTripAndRoutes(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		_, _ = w.Write([]byte("event: response.created\ndata: {\"response\":{\"id\":\"resp\"}}\n\nevent: response.output_text.delta\ndata: {\"delta\":\"你好\"}\n\nevent: response.completed\ndata: {\"response\":{\"id\":\"resp\",\"output\":[],\"usage\":{\"total_tokens\":1}}}\n\n"))
	}))
	defer upstream.Close()
	engine, err := newEngineServer(config{DataDir: filepath.Join(t.TempDir(), "data"), APIKey: "secret", BaseURL: upstream.URL, InternalAPIKey: "internal"})
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(engine.handler())
	defer server.Close()

	req, _ := http.NewRequest(http.MethodPost, server.URL+"/api/chat/run_sse", strings.NewReader(`{"message":"测试"}`))
	req.Header.Set("Authorization", "Bearer internal")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("run status = %d", resp.StatusCode)
	}
	var sessions struct {
		Sessions []sessionSummary `json:"sessions"`
	}
	listReq, _ := http.NewRequest(http.MethodGet, server.URL+"/api/chat/sessions", nil)
	listReq.Header.Set("Authorization", "Bearer internal")
	listResp, err := http.DefaultClient.Do(listReq)
	if err != nil {
		t.Fatal(err)
	}
	defer listResp.Body.Close()
	if err := json.NewDecoder(listResp.Body).Decode(&sessions); err != nil {
		t.Fatal(err)
	}
	if len(sessions.Sessions) != 1 || sessions.Sessions[0].Title != "测试" {
		t.Fatalf("sessions = %#v", sessions.Sessions)
	}
}

func TestEngineRequiresBearerToken(t *testing.T) {
	provider, _ := newDeepSeekProvider("secret", "http://localhost")
	store, _ := openSessionStore(t.TempDir())
	engine := &engineServer{key: "internal", store: store, provider: provider, runs: map[string]*activeRun{}}
	recorder := httptest.NewRecorder()
	engine.handler().ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/api/chat/models", nil))
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d", recorder.Code)
	}
}
