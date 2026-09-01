package main

import (
	"encoding/json"
	"time"
	_ "time/tzdata"
)

const (
	defaultBaseURL = "https://api.deepseek.com"
	chatModel      = "deepseek-v4-flash"
	visionModel    = "deepseek-v4-flash-vision-exp"
)

type config struct {
	DataDir        string `json:"dataDir"`
	APIKey         string `json:"apiKey"`
	BaseURL        string `json:"baseURL"`
	InternalAPIKey string `json:"internalAPIKey"`
	RuntimePath    string `json:"runtimePath"`
	Timezone       string `json:"timezone"`
	ProxyURL       string `json:"proxyURL"`
}

type attachment struct {
	Name     string `json:"name"`
	MIMEType string `json:"mimeType"`
	Data     string `json:"data,omitempty"`
	URL      string `json:"url,omitempty"`
}

type runRequest struct {
	SessionID string       `json:"sessionId"`
	Message   string       `json:"message"`
	Model     string       `json:"model"`
	Images    []attachment `json:"images"`
	Files     []attachment `json:"files"`
}

type part struct {
	Type     string          `json:"type"`
	Text     string          `json:"text,omitempty"`
	Name     string          `json:"name,omitempty"`
	CallID   string          `json:"callId,omitempty"`
	Status   string          `json:"status,omitempty"`
	Args     json.RawMessage `json:"args,omitempty"`
	Result   json.RawMessage `json:"result,omitempty"`
	Data     string          `json:"data,omitempty"`
	URL      string          `json:"url,omitempty"`
	MIMEType string          `json:"mimeType,omitempty"`
}

type usage struct {
	InputTokens    int `json:"inputTokens"`
	OutputTokens   int `json:"outputTokens"`
	ThinkingTokens int `json:"thinkingTokens"`
	CachedTokens   int `json:"cachedTokens"`
	TotalTokens    int `json:"totalTokens"`
}

type event struct {
	ID           string `json:"id,omitempty"`
	ResponseID   string `json:"responseId,omitempty"`
	InvocationID string `json:"invocationId,omitempty"`
	Author       string `json:"author"`
	Partial      bool   `json:"partial,omitempty"`
	Reset        bool   `json:"reset,omitempty"`
	Timestamp    string `json:"timestamp,omitempty"`
	Parts        []part `json:"parts"`
	Usage        *usage `json:"usage,omitempty"`
}

type turn struct {
	Message string            `json:"message"`
	Images  []attachment      `json:"images,omitempty"`
	Files   []attachment      `json:"files,omitempty"`
	Output  []json.RawMessage `json:"output,omitempty"`
}

type session struct {
	ID         string           `json:"id"`
	Title      string           `json:"title"`
	UpdatedAt  string           `json:"updatedAt"`
	Model      string           `json:"model"`
	Starred    bool             `json:"starred"`
	Events     []event          `json:"events"`
	Turns      []turn           `json:"turns"`
	Compaction *compactionState `json:"compaction,omitempty"`
}

// compactionState is a prompt overlay. Session events stay immutable; the
// summary is not a chat bubble and does not advance UpdatedAt.
type compactionState struct {
	Summary        string `json:"summary,omitempty"`
	CoveredEventID string `json:"coveredEventId,omitempty"`
}

type sessionSummary struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	UpdatedAt string `json:"updatedAt"`
	Model     string `json:"model"`
	Starred   bool   `json:"starred"`
}

func nowText() string { return time.Now().UTC().Format(time.RFC3339Nano) }
