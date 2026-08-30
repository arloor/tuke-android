package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

func main() {
	configPath := flag.String("config", "", "engine config path")
	flag.Parse()
	if *configPath == "" {
		fmt.Fprintln(os.Stderr, "--config is required")
		os.Exit(2)
	}
	raw, err := os.ReadFile(*configPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	var cfg config
	if err := json.Unmarshal(raw, &cfg); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	if err := os.MkdirAll(cfg.DataDir, 0o700); err != nil {
		failRuntime(cfg.RuntimePath, err)
	}
	engine, err := newEngineServer(cfg)
	if err != nil {
		failRuntime(cfg.RuntimePath, err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		failRuntime(cfg.RuntimePath, err)
	}
	server := &http.Server{Handler: engine.handler(), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 90 * time.Second}
	port := listener.Addr().(*net.TCPAddr).Port
	if err := writeRuntime(cfg.RuntimePath, map[string]any{"status": "running", "port": port, "pid": os.Getpid()}); err != nil {
		_ = listener.Close()
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	go func() {
		if err := server.Serve(listener); err != nil && err != http.ErrServerClosed {
			fmt.Fprintln(os.Stderr, err)
		}
	}()
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = server.Shutdown(ctx)
}

func writeRuntime(path string, value any) error {
	if path == "" {
		return fmt.Errorf("runtimePath is required")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return err
	}
	return os.WriteFile(path, raw, 0o600)
}

func failRuntime(path string, err error) {
	_ = writeRuntime(path, map[string]any{"status": "error", "error": err.Error()})
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}
