// Command copytrading-admin is the password-gated CopyTrading trader UI (SEE-126).
//
// It is a client of the template's existing JSON API: judges log in with named bcrypt passwords,
// this process presents PUBLISHER_API_TOKEN to loopback /v1, and the browser never sees that token.
// Serve it behind the gateway's existing HTTPS origin on /trader — not as a public /v1, not on the
// sidecar Funnel port, and not through compose.public.yaml.
//
//	copytrading-admin
//	copytrading-admin hash judge1   # prints name:bcrypt to stdout; append that line to the file
package main

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/BrRenat/SeekerAgentWallet/publisher/internal/admin"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "hash" {
		if err := hash(os.Args[2:], os.Stdin, os.Stdout); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}
	log := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
	if err := run(log); err != nil {
		log.Error("the trader UI stopped", "error", err)
		os.Exit(1)
	}
	log.Info("the trader UI stopped")
}

func hash(arguments []string, in io.Reader, out io.Writer) error {
	if len(arguments) != 1 {
		return fmt.Errorf("hash takes one name, then the password on stdin")
	}
	password, err := bufio.NewReader(in).ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return err
	}
	password = strings.TrimRight(password, "\r\n")
	line, err := admin.HashLine(arguments[0], password)
	if err != nil {
		return err
	}
	fmt.Fprintln(out, line)
	return nil
}

func run(log *slog.Logger) error {
	settings, problems := admin.Load(os.LookupEnv)
	if len(problems) > 0 {
		fmt.Fprintln(os.Stderr, "The trader UI cannot start:")
		for _, problem := range problems {
			fmt.Fprintf(os.Stderr, "  - %s\n", problem)
		}
		os.Exit(1)
	}
	passwords, err := admin.OpenFile(settings.PasswordsFile)
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	service := &http.Server{
		Addr: settings.ListenAddress,
		Handler: admin.New(admin.Plan{
			Config:    settings,
			Passwords: passwords,
			Log:       log,
		}).Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       2 * time.Minute,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelWarn),
	}

	failed := make(chan error, 1)
	go func() {
		log.Info("the trader UI is listening",
			"address", settings.ListenAddress,
			"path", settings.PublicPath,
			"api", settings.APIURL)
		if err := service.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			failed <- err
		}
	}()

	select {
	case err := <-failed:
		return err
	case <-ctx.Done():
	}

	log.Info("stopping")
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return service.Shutdown(shutdown)
}
