package main

import (
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"golang.org/x/net/html"
	"golang.org/x/net/html/charset"
	"golang.org/x/net/idna"
)

const (
	defaultFetchChars = 50_000
	maxFetchChars     = 200_000
	maxFetchWireBytes = int64(2 << 20)
	maxFetchBodyBytes = int64(4 << 20)
)

type webFetchInput struct {
	URL         string `json:"url"`
	ExtractMode string `json:"extract_mode,omitempty"`
	MaxChars    *int   `json:"max_chars,omitempty"`
}

type webFetchRedirect struct {
	From       string `json:"from"`
	To         string `json:"to"`
	StatusCode int    `json:"status_code"`
}

type webFetchOutput struct {
	RequestedURL     string             `json:"requested_url"`
	URL              string             `json:"url"`
	StatusCode       int                `json:"status_code"`
	MediaType        string             `json:"media_type"`
	Title            string             `json:"title,omitempty"`
	Content          string             `json:"content"`
	Truncated        bool               `json:"truncated"`
	TruncationReason string             `json:"truncation_reason,omitempty"`
	ContentSHA256    string             `json:"content_sha256"`
	RetrievedAt      string             `json:"retrieved_at"`
	Redirects        []webFetchRedirect `json:"redirects,omitempty"`
	ProxyFallback    bool               `json:"proxy_fallback,omitempty"`
}

type webFetchService struct {
	proxyURL     *url.URL
	resolver     fetchResolver
	dial         func(context.Context, string, string) (net.Conn, error)
	now          func() time.Time
	maxRedirects int
}

type fetchResolver interface {
	LookupNetIP(context.Context, string, string) ([]netip.Addr, error)
}

type fetchTarget struct {
	url      *url.URL
	hostname string
	port     string
}

func newWebFetchService(proxyURL *url.URL) *webFetchService {
	dialer := &net.Dialer{Timeout: 6 * time.Second, KeepAlive: -1}
	return &webFetchService{
		proxyURL: proxyURL, resolver: net.DefaultResolver, dial: dialer.DialContext,
		now: time.Now, maxRedirects: 3,
	}
}

func (s *webFetchService) fetch(ctx context.Context, input webFetchInput) (webFetchOutput, error) {
	target, err := validateFetchTarget(input.URL)
	if err != nil {
		return webFetchOutput{}, err
	}
	mode := strings.ToLower(strings.TrimSpace(input.ExtractMode))
	if mode == "" {
		mode = "markdown"
	}
	if mode != "markdown" && mode != "text" {
		return webFetchOutput{}, errors.New("extract_mode must be markdown or text")
	}
	maxChars := defaultFetchChars
	if input.MaxChars != nil {
		maxChars = *input.MaxChars
	}
	if maxChars < 1 || maxChars > maxFetchChars {
		return webFetchOutput{}, errors.New("max_chars must be between 1 and 200000")
	}
	ctx, cancel := context.WithTimeout(ctx, 35*time.Second)
	defer cancel()

	requested := target.url.String()
	redirects := make([]webFetchRedirect, 0, s.maxRedirects)
	visited := map[string]struct{}{requested: {}}
	usedFallback := false
	for hop := 0; ; hop++ {
		response, fallback, err := s.get(ctx, target)
		usedFallback = usedFallback || fallback
		if err != nil {
			return webFetchOutput{}, err
		}
		if redirectStatus(response.StatusCode) {
			location := strings.TrimSpace(response.Header.Get("Location"))
			_ = response.Body.Close()
			if hop >= s.maxRedirects {
				return webFetchOutput{}, errors.New("redirect limit exceeded")
			}
			nextURL, resolveErr := target.url.Parse(location)
			if resolveErr != nil {
				return webFetchOutput{}, errors.New("redirect target is invalid")
			}
			next, validateErr := validateFetchTarget(nextURL.String())
			if validateErr != nil {
				return webFetchOutput{}, fmt.Errorf("redirect target rejected: %w", validateErr)
			}
			if target.url.Scheme == "https" && next.url.Scheme != "https" {
				return webFetchOutput{}, errors.New("HTTPS downgrade is not allowed")
			}
			if _, duplicate := visited[next.url.String()]; duplicate {
				return webFetchOutput{}, errors.New("redirect loop detected")
			}
			visited[next.url.String()] = struct{}{}
			redirects = append(redirects, webFetchRedirect{From: target.url.String(), To: next.url.String(), StatusCode: response.StatusCode})
			target = next
			continue
		}
		if response.StatusCode < 200 || response.StatusCode >= 300 {
			_ = response.Body.Close()
			return webFetchOutput{}, fmt.Errorf("origin returned HTTP %d", response.StatusCode)
		}
		body, mediaType, err := readFetchBody(response)
		_ = response.Body.Close()
		if err != nil {
			return webFetchOutput{}, err
		}
		title := ""
		content := ""
		if mediaType == "text/html" || mediaType == "application/xhtml+xml" {
			document, parseErr := html.Parse(bytes.NewReader(body))
			if parseErr != nil {
				return webFetchOutput{}, errors.New("HTML could not be parsed")
			}
			title, content = extractHTMLText(document, mode)
		} else {
			content = normalizeFetchedText(string(body))
		}
		if content == "" {
			return webFetchOutput{}, errors.New("page contains no extractable text")
		}
		truncated := utf8.RuneCountInString(content) > maxChars
		if truncated {
			content = string([]rune(content)[:maxChars])
		}
		hash := sha256.Sum256([]byte(content))
		result := webFetchOutput{
			RequestedURL: requested, URL: target.url.String(), StatusCode: response.StatusCode, MediaType: mediaType,
			Title: title, Content: content, Truncated: truncated, ContentSHA256: hex.EncodeToString(hash[:]),
			RetrievedAt: s.now().UTC().Format(time.RFC3339Nano), Redirects: redirects, ProxyFallback: usedFallback,
		}
		if truncated {
			result.TruncationReason = "max_chars"
		}
		return result, nil
	}
}

// get honors Android's current HTTP proxy when present. If a local Clash
// listener disappeared after the engine started, only a transport failure
// triggers one direct retry; HTTP responses never bypass the configured proxy.
func (s *webFetchService) get(ctx context.Context, target fetchTarget) (*http.Response, bool, error) {
	if s.proxyURL != nil {
		if literal, parseErr := netip.ParseAddr(target.hostname); parseErr == nil && !publicIP(literal.Unmap()) {
			return nil, false, errors.New("target address is not public")
		}
		response, err := s.doGET(ctx, target, http.ProxyURL(s.proxyURL), s.dial)
		if err == nil {
			return response, false, nil
		}
		if ctx.Err() != nil {
			return nil, false, ctx.Err()
		}
		response, directErr := s.directGET(ctx, target)
		if directErr != nil {
			return nil, true, fmt.Errorf("proxy request failed (%v); direct fallback failed: %w", err, directErr)
		}
		return response, true, nil
	}
	response, err := s.directGET(ctx, target)
	return response, false, err
}

func (s *webFetchService) directGET(ctx context.Context, target fetchTarget) (*http.Response, error) {
	ips, err := s.resolver.LookupNetIP(ctx, "ip", target.hostname)
	if err != nil || len(ips) == 0 {
		return nil, errors.New("DNS lookup failed")
	}
	validated := make([]netip.Addr, 0, len(ips))
	for _, ip := range ips {
		ip = ip.Unmap()
		if !publicIP(ip) {
			return nil, errors.New("DNS answer contains a non-public address")
		}
		validated = append(validated, ip)
	}
	pinnedDial := func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil || !strings.EqualFold(strings.TrimSuffix(host, "."), target.hostname) || port != target.port {
			return nil, errors.New("transport attempted an unvalidated destination")
		}
		var lastErr error
		for _, ip := range validated {
			connection, dialErr := s.dial(ctx, network, net.JoinHostPort(ip.String(), port))
			if dialErr == nil {
				return connection, nil
			}
			lastErr = dialErr
		}
		return nil, lastErr
	}
	return s.doGET(ctx, target, nil, pinnedDial)
}

func (s *webFetchService) doGET(ctx context.Context, target fetchTarget, proxy func(*http.Request) (*url.URL, error), dial func(context.Context, string, string) (net.Conn, error)) (*http.Response, error) {
	transport := &http.Transport{
		Proxy: proxy, DialContext: dial, DisableCompression: true, DisableKeepAlives: true, ForceAttemptHTTP2: true,
		TLSHandshakeTimeout: 6 * time.Second, ResponseHeaderTimeout: 12 * time.Second, MaxResponseHeaderBytes: 64 << 10,
		TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12},
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, target.url.String(), nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "text/html, application/xhtml+xml, text/plain, text/markdown, application/json, application/xml, text/xml")
	request.Header.Set("Accept-Encoding", "gzip")
	request.Header.Set("User-Agent", "Tuke-Android/1 web_fetch")
	return client.Do(request)
}

func validateFetchTarget(raw string) (fetchTarget, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > 4096 || !utf8.ValidString(raw) {
		return fetchTarget{}, errors.New("url must contain 1 to 4096 UTF-8 bytes")
	}
	for _, r := range raw {
		if unicode.IsControl(r) || unicode.IsSpace(r) && r != ' ' {
			return fetchTarget{}, errors.New("url contains control characters")
		}
	}
	parsed, err := url.Parse(raw)
	if err != nil || !parsed.IsAbs() || parsed.Opaque != "" || parsed.User != nil {
		return fetchTarget{}, errors.New("url must be an absolute HTTP(S) URL without userinfo")
	}
	parsed.Scheme = strings.ToLower(parsed.Scheme)
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return fetchTarget{}, errors.New("only HTTP(S) URLs are supported")
	}
	host := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(parsed.Hostname())), ".")
	if ip, parseErr := netip.ParseAddr(host); parseErr == nil {
		host = ip.Unmap().String()
	} else {
		host, err = idna.Lookup.ToASCII(host)
		if err != nil || host == "" {
			return fetchTarget{}, errors.New("url has an invalid hostname")
		}
	}
	if host == "localhost" || strings.HasSuffix(host, ".localhost") || strings.HasSuffix(host, ".local") || strings.HasSuffix(host, ".internal") || strings.HasSuffix(host, ".lan") {
		return fetchTarget{}, errors.New("local hostnames are not allowed")
	}
	explicitPort := parsed.Port()
	port := explicitPort
	if port == "" {
		if parsed.Scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	} else if port != "80" && port != "443" {
		return fetchTarget{}, errors.New("only ports 80 and 443 are allowed")
	}
	parsed.Fragment = ""
	parsed.Host = host
	if explicitPort != "" {
		parsed.Host = net.JoinHostPort(host, explicitPort)
	} else if strings.Contains(host, ":") {
		parsed.Host = "[" + host + "]"
	}
	return fetchTarget{url: parsed, hostname: host, port: port}, nil
}

var blockedNetworks = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"), netip.MustParsePrefix("10.0.0.0/8"), netip.MustParsePrefix("100.64.0.0/10"),
	netip.MustParsePrefix("127.0.0.0/8"), netip.MustParsePrefix("169.254.0.0/16"), netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.0.0.0/24"), netip.MustParsePrefix("192.0.2.0/24"), netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("198.18.0.0/15"), netip.MustParsePrefix("198.51.100.0/24"), netip.MustParsePrefix("203.0.113.0/24"),
	netip.MustParsePrefix("224.0.0.0/4"), netip.MustParsePrefix("240.0.0.0/4"), netip.MustParsePrefix("::/128"),
	netip.MustParsePrefix("::1/128"), netip.MustParsePrefix("64:ff9b::/96"), netip.MustParsePrefix("100::/64"),
	netip.MustParsePrefix("2001::/23"), netip.MustParsePrefix("2001:db8::/32"), netip.MustParsePrefix("fc00::/7"),
	netip.MustParsePrefix("fe80::/10"), netip.MustParsePrefix("ff00::/8"),
}

func publicIP(ip netip.Addr) bool {
	if !ip.IsValid() || !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsUnspecified() || ip.IsMulticast() || ip.IsLinkLocalUnicast() {
		return false
	}
	for _, prefix := range blockedNetworks {
		if prefix.Contains(ip.Unmap()) {
			return false
		}
	}
	return true
}

func readFetchBody(response *http.Response) ([]byte, string, error) {
	if response.ContentLength > maxFetchWireBytes {
		return nil, "", errors.New("response exceeds the byte limit")
	}
	wire, err := io.ReadAll(io.LimitReader(response.Body, maxFetchWireBytes+1))
	if err != nil || int64(len(wire)) > maxFetchWireBytes {
		return nil, "", errors.New("response body exceeds the byte limit")
	}
	decoded := wire
	switch strings.ToLower(strings.TrimSpace(response.Header.Get("Content-Encoding"))) {
	case "", "identity":
	case "gzip":
		reader, gzipErr := gzip.NewReader(bytes.NewReader(wire))
		if gzipErr != nil {
			return nil, "", errors.New("gzip response is malformed")
		}
		decoded, err = io.ReadAll(io.LimitReader(reader, maxFetchBodyBytes+1))
		_ = reader.Close()
		if err != nil || int64(len(decoded)) > maxFetchBodyBytes {
			return nil, "", errors.New("decompressed response exceeds the byte limit")
		}
	default:
		return nil, "", errors.New("response content encoding is not supported")
	}
	contentType := strings.TrimSpace(response.Header.Get("Content-Type"))
	mediaType := ""
	if contentType != "" {
		mediaType, _, err = mime.ParseMediaType(contentType)
		if err != nil {
			return nil, "", errors.New("Content-Type is malformed")
		}
	}
	if mediaType == "" || mediaType == "application/octet-stream" {
		mediaType, _, _ = mime.ParseMediaType(http.DetectContentType(decoded[:min(len(decoded), 512)]))
		contentType = mediaType
	}
	switch strings.ToLower(mediaType) {
	case "text/html", "application/xhtml+xml", "text/plain", "text/markdown", "text/x-markdown", "application/json", "application/xml", "text/xml":
	default:
		return nil, "", errors.New("response media type is not supported")
	}
	reader, err := charset.NewReader(bytes.NewReader(decoded), contentType)
	if err != nil {
		return nil, "", errors.New("response character encoding is not supported")
	}
	utf8Body, err := io.ReadAll(io.LimitReader(reader, maxFetchBodyBytes+1))
	if err != nil || int64(len(utf8Body)) > maxFetchBodyBytes || !utf8.Valid(utf8Body) {
		return nil, "", errors.New("decoded response is invalid or too large")
	}
	return utf8Body, strings.ToLower(mediaType), nil
}

func extractHTMLText(document *html.Node, mode string) (string, string) {
	var title strings.Builder
	var body strings.Builder
	var walk func(*html.Node, bool)
	walk = func(node *html.Node, skip bool) {
		if node.Type == html.ElementNode {
			name := strings.ToLower(node.Data)
			if name == "script" || name == "style" || name == "noscript" || name == "svg" || name == "template" {
				skip = true
			}
			if !skip {
				switch name {
				case "br", "p", "div", "article", "section", "main", "header", "footer", "nav", "tr", "blockquote", "pre":
					body.WriteByte('\n')
				case "li":
					body.WriteString("\n- ")
				case "h1", "h2", "h3", "h4", "h5", "h6":
					body.WriteByte('\n')
					if mode == "markdown" {
						level, _ := strconv.Atoi(name[1:])
						body.WriteString(strings.Repeat("#", level) + " ")
					}
				}
			}
		}
		if node.Type == html.TextNode && !skip {
			if node.Parent != nil && strings.EqualFold(node.Parent.Data, "title") {
				title.WriteString(node.Data)
			} else {
				body.WriteString(node.Data)
				body.WriteByte(' ')
			}
		}
		for child := node.FirstChild; child != nil; child = child.NextSibling {
			walk(child, skip)
		}
	}
	walk(document, false)
	return strings.TrimSpace(strings.Join(strings.Fields(title.String()), " ")), normalizeFetchedText(body.String())
}

func normalizeFetchedText(value string) string {
	value = strings.ReplaceAll(value, "\r\n", "\n")
	value = strings.ReplaceAll(value, "\r", "\n")
	lines := strings.Split(value, "\n")
	result := make([]string, 0, len(lines))
	blank := false
	for _, line := range lines {
		line = strings.TrimSpace(strings.Join(strings.Fields(line), " "))
		if line == "" {
			if len(result) > 0 && !blank {
				result = append(result, "")
				blank = true
			}
			continue
		}
		result = append(result, line)
		blank = false
	}
	return strings.TrimSpace(strings.Join(result, "\n"))
}

func redirectStatus(status int) bool {
	return status == http.StatusMovedPermanently || status == http.StatusFound || status == http.StatusSeeOther || status == http.StatusTemporaryRedirect || status == http.StatusPermanentRedirect
}
