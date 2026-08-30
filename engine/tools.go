package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"
)

type localTools struct {
	location *time.Location
	now      func() time.Time
	fetch    *webFetchService
}

type toolFailure struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func newLocalTools(location *time.Location) *localTools {
	if location == nil {
		location = time.Local
	}
	return &localTools{location: location, now: time.Now}
}

func (t *localTools) configureWebFetch(proxyURL string) error {
	var proxy *url.URL
	if strings.TrimSpace(proxyURL) != "" {
		parsed, err := url.Parse(strings.TrimSpace(proxyURL))
		if err != nil || parsed.Scheme != "http" && parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil {
			return errors.New("系统 HTTP 代理地址无效")
		}
		proxy = parsed
	}
	t.fetch = newWebFetchService(proxy)
	return nil
}

func (t *localTools) definitions() []map[string]any {
	return []map[string]any{
		{
			"type": "function", "name": "current_time", "strict": false,
			"description": "Get the device's exact current date, time, timezone, UTC offset, and Unix timestamp. The system prompt already includes today's date and timezone; use only when the request depends on the exact current time of day.",
			"parameters":  map[string]any{"type": "object", "additionalProperties": false, "properties": map[string]any{}},
		},
		{
			"type": "function", "name": "web_fetch", "strict": false,
			"description": "Fetch one public HTTP(S) page without authentication or browser execution. Returns bounded markdown or text plus the final URL, redirects, MIME type, and content hash. Images, audio, video, archives, executables, and PDFs are not supported.",
			"parameters": map[string]any{
				"type": "object", "additionalProperties": false, "required": []string{"url"},
				"properties": map[string]any{
					"url":          map[string]any{"type": "string", "description": "Absolute public HTTP(S) URL to fetch without credentials."},
					"extract_mode": map[string]any{"type": "string", "enum": []string{"markdown", "text"}, "description": "Defaults to markdown."},
					"max_chars":    map[string]any{"type": "integer", "minimum": 1, "maximum": 200000, "description": "Defaults to 50000."},
				},
			},
		},
	}
}

func (t *localTools) execute(ctx context.Context, _ string, name string, arguments json.RawMessage) json.RawMessage {
	var value any
	var err error
	switch name {
	case "current_time":
		var input struct{}
		if err = decodeToolArguments(arguments, &input); err == nil {
			if contextErr := ctx.Err(); contextErr != nil {
				err = contextErr
			} else {
				current := t.now().In(t.location)
				zone, _ := current.Zone()
				value = map[string]any{
					"local_time": current.Format(time.RFC3339), "utc": current.UTC().Format(time.RFC3339),
					"date": current.Format(time.DateOnly), "timezone": t.location.String(),
					"zone_abbreviation": zone, "utc_offset": current.Format("-07:00"), "unix_seconds": current.Unix(),
				}
			}
		}
	case "web_fetch":
		var input webFetchInput
		if err = decodeToolArguments(arguments, &input); err == nil {
			if t.fetch == nil {
				err = errors.New("web_fetch is unavailable")
			} else {
				value, err = t.fetch.fetch(ctx, input)
			}
		}
	default:
		err = fmt.Errorf("unknown tool %q", name)
	}
	if err != nil {
		code := "invalid_argument"
		if errors.Is(err, context.Canceled) {
			code = "cancelled"
		} else if errors.Is(err, context.DeadlineExceeded) {
			code = "timeout"
		}
		value = map[string]any{"error": toolFailure{Code: code, Message: err.Error()}}
	}
	raw, marshalErr := json.Marshal(value)
	if marshalErr != nil {
		return json.RawMessage(`{"error":{"code":"unavailable","message":"tool result could not be encoded"}}`)
	}
	return raw
}

func decodeToolArguments(raw json.RawMessage, target any) error {
	if len(raw) == 0 {
		raw = json.RawMessage(`{}`)
	}
	decoder := json.NewDecoder(strings.NewReader(string(raw)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		return fmt.Errorf("invalid tool arguments: %w", err)
	}
	return nil
}
