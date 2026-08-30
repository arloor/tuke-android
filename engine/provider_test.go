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
	}}, "deepseek", func(providerDelta) {})
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
