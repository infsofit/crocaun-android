package transfer

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/schollz/croc/v10/src/comm"
	"github.com/schollz/croc/v10/src/croc"
)

const (
	ModeSend    = "send"
	ModeReceive = "receive"

	defaultRelayPassphrase = "pass123" // Croc v10.0.12 CLI default.
)

var (
	publicRelays = []string{
		"1.getcroc.com:9009",
		"2.getcroc.com:9009",
		"3.getcroc.com:9009",
		"4.getcroc.com:9009",
		"croc.schollz.com:9009",
	}
	workingDirectoryMu sync.Mutex
)

type relayCheckResult struct {
	Address   string `json:"address"`
	Available bool   `json:"available"`
	RTTMillis int64  `json:"rttMillis,omitempty"`
	Error     string `json:"error,omitempty"`
}

// CheckRelayServers checks the public Croc relay pool using the same ping/pong
// protocol as Windows Crocaun and returns ordered JSON for the Android UI.
func CheckRelayServers(timeoutMillis int64) string {
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout < time.Second {
		timeout = 4 * time.Second
	}
	if timeout > 10*time.Second {
		timeout = 10 * time.Second
	}
	results := make([]relayCheckResult, len(publicRelays))
	var wg sync.WaitGroup
	for i, address := range publicRelays {
		results[i].Address = address
		wg.Add(1)
		go func(i int, address string) {
			defer wg.Done()
			rtt, err := probeRelayAddress(address, timeout)
			if err != nil {
				results[i].Error = err.Error()
				return
			}
			results[i].Available = true
			results[i].RTTMillis = rtt.Milliseconds()
		}(i, address)
	}
	wg.Wait()
	encoded, err := json.Marshal(results)
	if err != nil {
		return "[]"
	}
	return string(encoded)
}

func probeRelayAddress(address string, timeout time.Duration) (time.Duration, error) {
	started := time.Now()
	conn, err := net.DialTimeout("tcp", address, timeout)
	if err != nil {
		return 0, err
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(timeout)); err != nil {
		return 0, err
	}
	channel := comm.New(conn)
	if err := channel.Send([]byte("ping")); err != nil {
		return 0, err
	}
	response, err := channel.Receive()
	if err != nil {
		return 0, err
	}
	if string(response) != "pong" {
		return 0, errors.New("relay did not return pong")
	}
	return time.Since(started), nil
}

// TransferSession is the narrow mobile facade for one standard Croc file transfer.
// Run is synchronous; Android must call it off the main thread and keep the
// process alive for the duration of a transfer.
type TransferSession struct {
	mu            sync.Mutex
	mode          string
	secret        string
	paths         []string
	outputDir     string
	relay         string
	relayPassword string
	status        string
	started       bool
}

// NewTransferSession validates and prepares a transfer. pathsJSON is a JSON
// array of filesystem paths for send mode and may be empty for receive mode.
// For receive mode, outputDir must already exist.
func NewTransferSession(mode, password, pathsJSON, outputDir, relay, relayPassword string) (*TransferSession, error) {
	if mode != ModeSend && mode != ModeReceive {
		return nil, fmt.Errorf("unsupported transfer mode %q", mode)
	}
	if password == "" {
		return nil, errors.New("room password must not be empty")
	}
	secret := makeSecret(password)
	if len(secret) < 6 {
		return nil, errors.New("room password is too short for Croc")
	}
	var paths []string
	if mode == ModeSend {
		if err := json.Unmarshal([]byte(pathsJSON), &paths); err != nil {
			return nil, errors.New("send paths must be a JSON array of file paths")
		}
		if len(paths) == 0 {
			return nil, errors.New("at least one file is required")
		}
		absolutePaths := make([]string, 0, len(paths))
		for _, p := range paths {
			absolutePath, err := filepath.Abs(p)
			if err != nil {
				return nil, fmt.Errorf("cannot resolve selected file path: %w", err)
			}
			p = absolutePath
			info, err := os.Stat(p)
			if err != nil {
				return nil, fmt.Errorf("cannot access selected file %q: %w", filepath.Base(p), err)
			}
			if !info.Mode().IsRegular() {
				return nil, fmt.Errorf("selected path is not a regular file: %s", filepath.Base(p))
			}
			absolutePaths = append(absolutePaths, p)
		}
		paths = absolutePaths
	} else {
		absoluteOutputDir, err := filepath.Abs(outputDir)
		if err != nil {
			return nil, fmt.Errorf("cannot resolve receive staging directory: %w", err)
		}
		outputDir = absoluteOutputDir
		info, err := os.Stat(outputDir)
		if err != nil {
			return nil, fmt.Errorf("cannot access receive staging directory: %w", err)
		}
		if !info.IsDir() {
			return nil, errors.New("receive staging path is not a directory")
		}
	}

	relayPassword = strings.TrimSpace(relayPassword)
	if relayPassword == "" {
		relayPassword = defaultRelayPassphrase
	}
	return &TransferSession{
		mode:          mode,
		secret:        secret,
		paths:         append([]string(nil), paths...),
		outputDir:     outputDir,
		relay:         strings.TrimSpace(relay),
		relayPassword: relayPassword,
		status:        "ready",
	}, nil
}

// Run performs the transfer, trying the same password-derived relay order as Windows Crocaun.
func (s *TransferSession) Run() error {
	s.mu.Lock()
	if s.started {
		s.mu.Unlock()
		return errors.New("transfer session can only be run once")
	}
	s.started = true
	s.status = "connecting"
	s.mu.Unlock()

	var lastErr error
	for _, address := range relayOrder(s.secret, s.relay) {
		s.setStatus("connecting to " + address)
		client, err := croc.New(crocOptions(s, address))
		if err != nil {
			lastErr = err
			continue
		}
		if s.mode == ModeSend {
			files, emptyFolders, folderCount, infoErr := croc.GetFilesInfo(s.paths, false, false)
			if infoErr != nil {
				lastErr = infoErr
				break
			}
			lastErr = client.Send(files, emptyFolders, folderCount)
		} else {
			lastErr = s.receive(client)
		}
		if lastErr == nil {
			s.setStatus("completed")
			return nil
		}
	}
	s.setStatus("failed")
	if lastErr == nil {
		lastErr = errors.New("no relay was available")
	}
	return fmt.Errorf("Croc transfer failed: %w", lastErr)
}

// Status returns a user-safe progress stage; it never includes the room password or Croc secret.
func (s *TransferSession) Status() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.status
}

func (s *TransferSession) setStatus(status string) {
	s.mu.Lock()
	s.status = status
	s.mu.Unlock()
}

func (s *TransferSession) receive(client *croc.Client) error {
	workingDirectoryMu.Lock()
	defer workingDirectoryMu.Unlock()

	previous, err := os.Getwd()
	if err != nil {
		return err
	}
	if err := os.Chdir(s.outputDir); err != nil {
		return err
	}
	defer func() { _ = os.Chdir(previous) }()
	return client.Receive()
}

func crocOptions(s *TransferSession, relay string) croc.Options {
	ports := []string(nil)
	isSender := s.mode == ModeSend
	if isSender {
		ports = []string{"9009", "9010", "9011", "9012", "9013"}
	}
	return croc.Options{
		IsSender:      isSender,
		SharedSecret:  s.secret,
		Debug:         false,
		RelayAddress:  relay,
		RelayAddress6: "", // Windows Crocaun passes --relay explicitly and Croc clears relay6.
		RelayPorts:    ports,
		RelayPassword: s.relayPassword,
		NoPrompt:      true,
		DisableLocal:  true, // Windows sender uses --no-local; relay path is the compatible baseline.
		IgnoreStdin:   isSender,
		Overwrite:     !isSender, // receive happens in a new private staging directory.
		Curve:         "p256",
		HashAlgorithm: "xxhash",
	}
}

func relayOrder(secret, selected string) []string {
	if selected = strings.TrimSpace(selected); selected != "" {
		return []string{selected}
	}
	h := sha256.Sum256([]byte("crocau-relay:" + secret))
	start := int(h[0]) % 4
	order := make([]string, 0, len(publicRelays))
	for i := 0; i < 4; i++ {
		order = append(order, publicRelays[(start+i)%4])
	}
	return append(order, publicRelays[4])
}

func makeSecret(password string) string {
	h := sha256.Sum256([]byte("crocau-room:" + password))
	return hex.EncodeToString(h[:])[:4] + "-" + password + "~crocau"
}
