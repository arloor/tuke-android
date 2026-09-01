package main

import (
	"context"
	"encoding/base64"
	"fmt"
	"os"
	"strings"
	"unicode/utf8"
)

const (
	defaultMaxInputTokens          = 500_000
	defaultTriggerRatio            = 0.80
	defaultEventRetentionSize      = 10
	defaultImageTokens             = 1_600
	defaultDocumentTokens          = 4_000
	maxToolContentChars            = 2_000
	maxTranscriptChars             = 200_000
	summarizerTimeoutSeconds       = 60
	conversationHistoryPlaceholder = "{conversation_history}"
)

// defaultSummarizerPrompt matches ADK Go v2.3.0 LLMSummarizer: tail retention
// reseeds each summary with the previous one, so durable facts must be copied
// forward verbatim or they decay into labels.
const defaultSummarizerPrompt = "The following is a conversation history between a user and an AI agent." +
	" It may or may not start from a compacted history. Please identify and" +
	" reiterate the user request, summarize the context so far, focusing on" +
	" key decisions made and information obtained, as well as any unresolved" +
	" questions or tasks. " +
	"CRITICAL INSTRUCTIONS: " +
	"1. Explicitly identify and state the primary language used by the user " +
	`at the top of your summary (e.g., "Conversation Language: English"). ` +
	"2. If the agent called any tools, accurately list the exact tool names " +
	"used to maintain tool grounding. " +
	`3. Maintain a section titled "Durable facts" listing every concrete ` +
	"detail the user has stated: identifiers, names, dates, numbers, chosen " +
	"options and the reasons given for them. Copy each one verbatim. This " +
	"history may already be a summary of a summary, so any durable fact " +
	"present in the history MUST be carried forward unchanged, even if it is " +
	"old and the recent turns are about something else. Never drop or " +
	"generalize a durable fact to save space; drop narrative instead. " +
	"The rest of the summary should be concise and capture the" +
	" essence of the interaction.\n\n" + conversationHistoryPlaceholder

type compactionConfig struct {
	TokenThreshold     int
	EventRetentionSize int
}

func defaultCompactionConfig() compactionConfig {
	return compactionConfig{
		TokenThreshold:     int(float64(defaultMaxInputTokens) * defaultTriggerRatio),
		EventRetentionSize: defaultEventRetentionSize,
	}
}

func (s *engineServer) compactIfNeeded(ctx context.Context, sessionID string) ([]turn, error) {
	value, ok := s.store.get(sessionID)
	if !ok {
		return nil, os.ErrNotExist
	}
	current := promptTurns(value)
	cfg := s.compaction
	if cfg.TokenThreshold <= 0 || cfg.EventRetentionSize < 1 {
		return current, nil
	}
	if estimateTurns(current) < cfg.TokenThreshold {
		return current, nil
	}
	window := compactionWindow(value.Events, value.Compaction, cfg.EventRetentionSize)
	if len(window) == 0 {
		return current, nil
	}
	previous := ""
	if value.Compaction != nil {
		previous = value.Compaction.Summary
	}
	summary, err := s.provider.summarize(ctx, previous, window)
	if err != nil {
		return current, err
	}
	state := compactionState{Summary: summary, CoveredEventID: window[len(window)-1].ID}
	if err := s.store.saveCompaction(sessionID, state); err != nil {
		return current, err
	}
	value.Compaction = &state
	return promptTurns(value), nil
}

func visibleEvents(events []event) []event {
	out := make([]event, 0, len(events))
	for _, item := range events {
		if isCompactionEvent(item) {
			continue
		}
		out = append(out, item)
	}
	return out
}

func isCompactionEvent(item event) bool {
	return item.Author == "compaction" || strings.EqualFold(item.Author, "compaction")
}

func promptTurns(value *session) []turn {
	if value == nil {
		return nil
	}
	start := coveredTurnCount(value.Events, value.Compaction)
	if start > len(value.Turns) {
		start = len(value.Turns)
	}
	tail := append([]turn(nil), value.Turns[start:]...)
	if value.Compaction == nil || strings.TrimSpace(value.Compaction.Summary) == "" {
		return tail
	}
	return append([]turn{{Message: "此前对话摘要：\n" + strings.TrimSpace(value.Compaction.Summary)}}, tail...)
}

func coveredTurnCount(events []event, state *compactionState) int {
	if state == nil || state.CoveredEventID == "" {
		return 0
	}
	users := 0
	for _, item := range events {
		if item.Author == "user" {
			users++
		}
		if item.ID == state.CoveredEventID {
			return users
		}
	}
	return 0
}

func compactionWindow(events []event, state *compactionState, retention int) []event {
	if retention < 1 || len(events) <= retention {
		return nil
	}
	start := 0
	if state != nil && state.CoveredEventID != "" {
		for index, item := range events {
			if item.ID == state.CoveredEventID {
				start = index + 1
				break
			}
		}
	}
	end := len(events) - retention
	if end <= start {
		return nil
	}
	window := events[start:end]
	for len(window) > 0 && window[0].Author != "user" {
		window = window[1:]
	}
	if len(window) > 0 && window[len(window)-1].Author == "user" {
		window = window[:len(window)-1]
	}
	if len(window) == 0 {
		return nil
	}
	return window
}

func estimateTurns(turns []turn) int {
	total := 0
	for _, item := range turns {
		total += estimateTextTokens(item.Message)
		total += defaultImageTokens * len(item.Images)
		for _, file := range item.Files {
			total += estimateFileTokens(file)
		}
		for _, raw := range item.Output {
			total += estimateTextTokens(string(raw))
		}
	}
	return total
}

func estimateFileTokens(file attachment) int {
	if file.Data == "" {
		return defaultDocumentTokens
	}
	decoded, err := base64.StdEncoding.DecodeString(file.Data)
	if err != nil {
		return defaultDocumentTokens
	}
	if textAttachmentMIME(file.MIMEType) && utf8.Valid(decoded) {
		return estimateTextTokens(string(decoded))
	}
	return defaultDocumentTokens
}

func estimateTextTokens(value string) int {
	if value == "" {
		return 0
	}
	wide := 0
	ascii := 0
	for _, r := range value {
		if r <= 0x7F {
			ascii++
			continue
		}
		wide++
	}
	return wide + (ascii+3)/4
}

func renderTranscript(previous string, events []event, partCap, totalCap int) (string, error) {
	transcript := formatTranscript(previous, events, partCap)
	if totalCap < 0 || utf8.RuneCountInString(transcript) <= totalCap {
		return transcript, nil
	}
	parts := countTranscriptParts(previous, events)
	if parts > 0 {
		shrunkCap := totalCap/parts - 40
		if shrunkCap > 0 && (partCap < 0 || shrunkCap < partCap) {
			shrunk := formatTranscript(previous, events, shrunkCap)
			if utf8.RuneCountInString(shrunk) < utf8.RuneCountInString(transcript) {
				transcript = shrunk
			}
		}
	}
	if utf8.RuneCountInString(transcript) <= totalCap {
		return transcript, nil
	}
	return "", fmt.Errorf("rendered transcript is %d characters, over the %d limit", utf8.RuneCountInString(transcript), totalCap)
}

func countTranscriptParts(previous string, events []event) int {
	n := 0
	if strings.TrimSpace(previous) != "" {
		n++
	}
	for _, item := range events {
		for _, p := range item.Parts {
			if transcriptLine(item.Author, p, 8) != "" {
				n++
			}
		}
	}
	return n
}

func formatTranscript(previous string, events []event, cap int) string {
	lines := make([]string, 0, 8)
	if strings.TrimSpace(previous) != "" {
		lines = append(lines, "user: [compacted history] "+escapeLines(truncateRunesTo(strings.TrimSpace(previous), cap)))
	}
	for _, item := range events {
		for _, p := range item.Parts {
			if line := transcriptLine(item.Author, p, cap); line != "" {
				lines = append(lines, line)
			}
		}
	}
	return strings.Join(lines, "\n")
}

func transcriptLine(author string, p part, cap int) string {
	switch p.Type {
	case "thinking":
		if strings.TrimSpace(p.Text) == "" {
			return ""
		}
		return escapeLines(author) + " (thought): " + escapeLines(truncateRunesTo(p.Text, cap))
	case "text":
		if strings.TrimSpace(p.Text) == "" {
			return ""
		}
		return escapeLines(author) + ": " + escapeLines(truncateRunesTo(p.Text, cap))
	case "tool_call":
		return escapeLines(author) + " called tool: " + escapeLines(p.Name) + "(" + escapeLines(truncateRunesTo(string(p.Args), cap)) + ")"
	case "tool_result":
		return "Tool response from " + escapeLines(p.Name) + ": " + escapeLines(truncateRunesTo(string(p.Result), cap))
	case "image":
		kind := strings.TrimSpace(p.MIMEType)
		if kind == "" {
			kind = "image"
		}
		return escapeLines(author) + ": [" + escapeLines(kind) + " attachment]"
	case "file":
		kind := strings.TrimSpace(p.MIMEType)
		if kind == "" {
			kind = "file"
		}
		return escapeLines(author) + ": [" + escapeLines(kind) + " attachment]"
	default:
		return ""
	}
}

func truncateRunesTo(value string, cap int) string {
	if cap < 0 || utf8.RuneCountInString(value) <= cap {
		return value
	}
	runes := []rune(value)
	return fmt.Sprintf("%s... [truncated %d chars]", string(runes[:cap]), len(runes)-cap)
}

const lineBreakers = "\r\n\v\f\u0085\u2028\u2029"

func escapeLines(text string) string {
	if !strings.ContainsAny(text, lineBreakers) {
		return text
	}
	return strings.NewReplacer(
		"\r\n", "\\n",
		"\r", "\\n",
		"\n", "\\n",
		"\v", "\\n",
		"\f", "\\n",
		"\u0085", "\\n",
		"\u2028", "\\n",
		"\u2029", "\\n",
	).Replace(text)
}
