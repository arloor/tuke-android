package main

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestCalendarContextAndCurrentTimeUseDeviceTimezone(t *testing.T) {
	location := time.FixedZone("CST", 8*60*60)
	fixed := time.Date(2026, time.August, 31, 23, 45, 6, 0, location)
	if got := calendarContext(fixed); got != "当前日期：2026-08-31（星期一）；时区：CST（UTC+08:00）" {
		t.Fatalf("calendar context = %q", got)
	}
	tools := newLocalTools(location)
	tools.now = func() time.Time { return fixed }
	raw := tools.execute(context.Background(), "session", "current_time", json.RawMessage(`{}`))
	var result map[string]any
	if err := json.Unmarshal(raw, &result); err != nil {
		t.Fatal(err)
	}
	if result["local_time"] != "2026-08-31T23:45:06+08:00" || result["utc_offset"] != "+08:00" || result["date"] != "2026-08-31" {
		t.Fatalf("current_time = %#v", result)
	}
}

type fixedFetchResolver struct{ addresses []netip.Addr }

func (r fixedFetchResolver) LookupNetIP(context.Context, string, string) ([]netip.Addr, error) {
	return r.addresses, nil
}

func TestWebFetchFallsBackToDirectWhenClashProxyIsDown(t *testing.T) {
	origin := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = writer.Write([]byte(`<html><head><title>示例页</title></head><body><h1>标题</h1><p>正文内容</p></body></html>`))
	}))
	defer origin.Close()
	originAddress := strings.TrimPrefix(origin.URL, "http://")
	deadProxy, err := url.Parse("http://127.0.0.1:1")
	if err != nil {
		t.Fatal(err)
	}
	service := newWebFetchService(deadProxy)
	service.resolver = fixedFetchResolver{addresses: []netip.Addr{netip.MustParseAddr("93.184.216.34")}}
	realDialer := &net.Dialer{Timeout: time.Second}
	service.dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		if address == "93.184.216.34:80" {
			address = originAddress
		}
		return realDialer.DialContext(ctx, network, address)
	}
	result, err := service.fetch(context.Background(), webFetchInput{URL: "http://public.example/article"})
	if err != nil {
		t.Fatal(err)
	}
	if !result.ProxyFallback || result.Title != "示例页" || !strings.Contains(result.Content, "正文内容") {
		t.Fatalf("web_fetch result = %#v", result)
	}
	service.proxyURL = nil
	direct, err := service.fetch(context.Background(), webFetchInput{URL: "http://public.example/article"})
	if err != nil {
		t.Fatal(err)
	}
	if direct.ProxyFallback || direct.Title != "示例页" || !strings.Contains(direct.Content, "正文内容") {
		t.Fatalf("direct web_fetch result = %#v", direct)
	}
}

func TestWebFetchRejectsPrivateTargets(t *testing.T) {
	service := newWebFetchService(nil)
	_, err := service.fetch(context.Background(), webFetchInput{URL: "http://127.0.0.1/secret"})
	if err == nil || !strings.Contains(err.Error(), "non-public") {
		t.Fatalf("private target error = %v", err)
	}
}
