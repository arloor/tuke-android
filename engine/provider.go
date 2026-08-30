package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const mobileInstruction = `你是运行在 Android 应用中的 AI 助手。请直接解决用户的问题，使用清晰、准确、适合手机阅读的中文；用户使用其他语言时跟随用户。需要最新信息时可使用联网搜索。不要声称执行了当前对话未提供的操作。`

type providerResult struct {
	ResponseID string
	Text       string
	Thinking   string
	Output     []json.RawMessage
	Usage      usage
}

type providerDelta struct {
	ResponseID string
	Part       part
}

type deepSeekProvider struct {
	apiKey  string
	baseURL string
	client  *http.Client
}

func newDeepSeekProvider(apiKey, rawBaseURL string) (*deepSeekProvider, error) {
	base := strings.TrimSpace(rawBaseURL)
	if base == "" {
		base = defaultBaseURL
	}
	parsed, err := url.Parse(base)
	if err != nil || parsed.Host == "" || parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" {
		return nil, errors.New("DeepSeek API 地址无效")
	}
	local := parsed.Hostname() == "127.0.0.1" || parsed.Hostname() == "localhost"
	if parsed.Scheme != "https" && !(local && parsed.Scheme == "http") {
		return nil, errors.New("DeepSeek API 地址必须使用 HTTPS")
	}
	if strings.TrimSpace(apiKey) == "" {
		return nil, errors.New("DeepSeek API Key 不能为空")
	}
	client := &http.Client{
		Timeout:       10 * time.Minute,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	return &deepSeekProvider{apiKey: strings.TrimSpace(apiKey), baseURL: strings.TrimRight(base, "/"), client: client}, nil
}

func userInput(t turn) map[string]any {
	content := make([]map[string]any, 0, 1+len(t.Images)+len(t.Files))
	if t.Message != "" {
		content = append(content, map[string]any{"type": "input_text", "text": t.Message})
	}
	for _, image := range t.Images {
		imageURL := image.URL
		if imageURL == "" && image.Data != "" {
			imageURL = "data:" + image.MIMEType + ";base64," + image.Data
		}
		content = append(content, map[string]any{"type": "input_image", "image_url": imageURL, "detail": "high"})
	}
	for _, file := range t.Files {
		item := map[string]any{"type": "input_file", "filename": file.Name}
		if file.Data != "" {
			item["file_data"] = "data:" + file.MIMEType + ";base64," + file.Data
		} else {
			item["file_url"] = file.URL
		}
		content = append(content, item)
	}
	return map[string]any{"type": "message", "role": "user", "content": content}
}

func (p *deepSeekProvider) stream(ctx context.Context, turns []turn, model string, onDelta func(providerDelta)) (providerResult, error) {
	input := make([]any, 0, len(turns)*3)
	hasImage := false
	for _, value := range turns {
		input = append(input, userInput(value))
		if len(value.Images) > 0 {
			hasImage = true
		}
		for _, raw := range value.Output {
			input = append(input, raw)
		}
	}
	selectedModel := chatModel
	if hasImage {
		selectedModel = visionModel
	}
	requestBody := map[string]any{
		"model":        selectedModel,
		"instructions": mobileInstruction,
		"input":        input,
		"stream":       true,
		"reasoning":    map[string]any{"effort": "high"},
		"tools":        []map[string]any{{"type": "web_search"}},
	}
	_ = model // The app exposes one stable provider name; concrete model selection stays internal.
	raw, err := json.Marshal(requestBody)
	if err != nil {
		return providerResult{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.baseURL+"/responses", bytes.NewReader(raw))
	if err != nil {
		return providerResult{}, err
	}
	req.Header.Set("Authorization", "Bearer "+p.apiKey)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "text/event-stream")
	resp, err := p.client.Do(req)
	if err != nil {
		return providerResult{}, fmt.Errorf("DeepSeek 请求失败: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		message, _ := io.ReadAll(io.LimitReader(resp.Body, 1024))
		return providerResult{}, fmt.Errorf("DeepSeek 返回 %d: %s", resp.StatusCode, strings.TrimSpace(string(message)))
	}

	var result providerResult
	scanner := bufio.NewScanner(io.LimitReader(resp.Body, 32<<20))
	scanner.Buffer(make([]byte, 64<<10), 2<<20)
	var eventName string
	var dataLines []string
	dispatch := func() error {
		if len(dataLines) == 0 {
			eventName = ""
			return nil
		}
		data := strings.Join(dataLines, "\n")
		dataLines = dataLines[:0]
		if data == "[DONE]" {
			return nil
		}
		var payload map[string]json.RawMessage
		if err := json.Unmarshal([]byte(data), &payload); err != nil {
			return fmt.Errorf("DeepSeek 流数据无效: %w", err)
		}
		typeName := eventName
		if rawType := payload["type"]; typeName == "" && rawType != nil {
			_ = json.Unmarshal(rawType, &typeName)
		}
		eventName = ""
		switch typeName {
		case "response.created":
			var response struct {
				ID string `json:"id"`
			}
			_ = json.Unmarshal(payload["response"], &response)
			result.ResponseID = response.ID
		case "response.reasoning_text.delta":
			var delta string
			_ = json.Unmarshal(payload["delta"], &delta)
			result.Thinking += delta
			if delta != "" {
				onDelta(providerDelta{result.ResponseID, part{Type: "thinking", Text: delta}})
			}
		case "response.output_text.delta", "response.refusal.delta":
			var delta string
			_ = json.Unmarshal(payload["delta"], &delta)
			result.Text += delta
			if delta != "" {
				onDelta(providerDelta{result.ResponseID, part{Type: "text", Text: delta}})
			}
		case "response.web_search_call.in_progress", "response.web_search_call.searching", "response.web_search_call.completed", "response.web_search_call.failed":
			status := strings.TrimPrefix(typeName, "response.web_search_call.")
			var itemID string
			_ = json.Unmarshal(payload["item_id"], &itemID)
			onDelta(providerDelta{result.ResponseID, part{Type: "hosted_tool_status", Name: "web_search", CallID: itemID, Status: status}})
		case "response.completed", "response.incomplete":
			var completed struct {
				ID     string            `json:"id"`
				Output []json.RawMessage `json:"output"`
				Usage  struct {
					InputTokens  int `json:"input_tokens"`
					OutputTokens int `json:"output_tokens"`
					TotalTokens  int `json:"total_tokens"`
					InputDetails struct {
						CachedTokens int `json:"cached_tokens"`
					} `json:"input_tokens_details"`
					OutputDetails struct {
						ReasoningTokens int `json:"reasoning_tokens"`
					} `json:"output_tokens_details"`
				} `json:"usage"`
			}
			if err := json.Unmarshal(payload["response"], &completed); err != nil {
				return err
			}
			if completed.ID != "" {
				result.ResponseID = completed.ID
			}
			result.Output = completed.Output
			result.Usage = usage{completed.Usage.InputTokens, completed.Usage.OutputTokens, completed.Usage.OutputDetails.ReasoningTokens, completed.Usage.InputDetails.CachedTokens, completed.Usage.TotalTokens}
		case "error", "response.failed":
			return fmt.Errorf("DeepSeek 生成失败: %s", data)
		}
		return nil
	}
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			if err := dispatch(); err != nil {
				return providerResult{}, err
			}
			continue
		}
		if strings.HasPrefix(line, "event:") {
			eventName = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		}
		if strings.HasPrefix(line, "data:") {
			dataLines = append(dataLines, strings.TrimSpace(strings.TrimPrefix(line, "data:")))
		}
	}
	if err := dispatch(); err != nil {
		return providerResult{}, err
	}
	if err := scanner.Err(); err != nil {
		return providerResult{}, fmt.Errorf("读取 DeepSeek 流失败: %w", err)
	}
	if result.ResponseID == "" {
		return providerResult{}, errors.New("DeepSeek 响应缺少 response id")
	}
	return result, nil
}
