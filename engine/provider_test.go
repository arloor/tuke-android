package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestDeepSeekStreamAndReplay(t *testing.T) {
	var captured map[string]any
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/responses" || r.Header.Get("Authorization") != "Bearer secret" {
			t.Fatalf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		if err := json.NewDecoder(r.Body).Decode(&captured); err != nil {
			t.Fatal(err)
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "event: response.created\ndata: {\"response\":{\"id\":\"resp-1\"}}\n\n")
		fmt.Fprint(w, "event: response.reasoning_text.delta\ndata: {\"delta\":\"想\"}\n\n")
		fmt.Fprint(w, "event: response.web_search_call.searching\ndata: {\"item_id\":\"search-1\"}\n\n")
		fmt.Fprint(w, "event: response.output_text.delta\ndata: {\"delta\":\"答案\"}\n\n")
		fmt.Fprint(w, "event: response.completed\ndata: {\"response\":{\"id\":\"resp-1\",\"output\":[{\"type\":\"reasoning\",\"id\":\"r-1\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"想\"}]}],\"usage\":{\"input_tokens\":2,\"output_tokens\":3,\"total_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":1},\"output_tokens_details\":{\"reasoning_tokens\":1}}}}\n\n")
	}))
	defer upstream.Close()
	provider, err := newDeepSeekProvider("secret", upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	result, err := provider.stream(context.Background(), []turn{{
		Message: "你好",
		Images:  []attachment{{Name: "a.png", MIMEType: "image/png", Data: "cG5n"}},
		Files: []attachment{{
			Name: "windows.xml", MIMEType: "text/xml",
			Data: base64.StdEncoding.EncodeToString([]byte(`<window title="测试" />`)),
		}},
	}}, "deepseek", "session-1", func(providerDelta) {})
	if err != nil {
		t.Fatal(err)
	}
	if result.Text != "答案" || result.Thinking != "想" || result.Usage.TotalTokens != 5 || len(result.Output) != 1 {
		t.Fatalf("unexpected result: %#v", result)
	}
	if captured["model"] != visionModel || captured["stream"] != true {
		t.Fatalf("unexpected request: %#v", captured)
	}
	input := captured["input"].([]any)
	content := input[0].(map[string]any)["content"].([]any)
	if content[1].(map[string]any)["image_url"] != "data:image/png;base64,cG5n" {
		t.Fatalf("unexpected image: %#v", content[1])
	}
	textFile := content[2].(map[string]any)
	if textFile["type"] != "input_text" || !strings.Contains(textFile["text"].(string), `<window title="测试" />`) {
		t.Fatalf("unexpected text file: %#v", textFile)
	}
}

func TestDeepSeekBaseURLRejectsInsecureRemote(t *testing.T) {
	if _, err := newDeepSeekProvider("secret", "http://example.com"); err == nil {
		t.Fatal("expected insecure URL rejection")
	}
}

func TestUserInputRejectsUnsupportedDocument(t *testing.T) {
	_, err := userInput(turn{Files: []attachment{{
		Name: "report.pdf", MIMEType: "application/pdf", Data: "cGRm",
	}}})
	if err == nil || !strings.Contains(err.Error(), "仅支持图片和 UTF-8 文本附件") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestDeepSeekRunsCurrentTimeToolAndContinues(t *testing.T) {
	requests := 0
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests++
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if requests == 1 {
			instructions := body["instructions"].(string)
			if !strings.Contains(instructions, "当前日期：2026-08-31（星期一）") || !strings.Contains(instructions, "时区：CST（UTC+08:00）") {
				t.Errorf("instructions = %q", instructions)
			}
			tools := body["tools"].([]any)
			if len(tools) != 3 || tools[1].(map[string]any)["name"] != "current_time" || tools[2].(map[string]any)["name"] != "web_fetch" {
				t.Errorf("tools = %#v", tools)
			}
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprint(w, "event: response.created\ndata: {\"response\":{\"id\":\"resp-tool\"}}\n\n")
			fmt.Fprint(w, "event: response.completed\ndata: {\"response\":{\"id\":\"resp-tool\",\"output\":[{\"type\":\"function_call\",\"id\":\"fc-1\",\"call_id\":\"call-1\",\"name\":\"current_time\",\"arguments\":\"{}\"}],\"usage\":{\"total_tokens\":2}}}\n\n")
			return
		}
		input := body["input"].([]any)
		last := input[len(input)-1].(map[string]any)
		if last["type"] != "function_call_output" || last["call_id"] != "call-1" || !strings.Contains(last["output"].(string), "2026-08-31T23:45:06+08:00") {
			t.Errorf("function output = %#v", last)
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "event: response.created\ndata: {\"response\":{\"id\":\"resp-final\"}}\n\n")
		fmt.Fprint(w, "event: response.output_text.delta\ndata: {\"delta\":\"现在是 23:45\"}\n\n")
		fmt.Fprint(w, "event: response.completed\ndata: {\"response\":{\"id\":\"resp-final\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"现在是 23:45\"}]}],\"usage\":{\"total_tokens\":3}}}\n\n")
	}))
	defer upstream.Close()

	provider, err := newDeepSeekProvider("secret", upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	location := time.FixedZone("CST", 8*60*60)
	fixed := time.Date(2026, time.August, 31, 23, 45, 6, 0, location)
	provider.configureLocalTools(location)
	provider.tools.now = func() time.Time { return fixed }
	provider.now = func() time.Time { return fixed }
	if err := provider.configureWebFetch(""); err != nil {
		t.Fatal(err)
	}
	result, err := provider.stream(context.Background(), []turn{{Message: "现在几点"}}, "deepseek", "session-1", func(providerDelta) {})
	if err != nil {
		t.Fatal(err)
	}
	if requests != 2 || result.Text != "现在是 23:45" || result.Usage.TotalTokens != 5 || len(result.Parts) != 3 || len(result.Output) != 3 {
		t.Fatalf("result = %#v, requests = %d", result, requests)
	}
}
