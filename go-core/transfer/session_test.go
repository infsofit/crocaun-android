package transfer

import (
	"encoding/json"
	"errors"
	"net"
	"os"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"github.com/schollz/croc/v10/src/comm"
)

func TestMakeSecretMatchesWindowsGoldenValues(t *testing.T) {
	tests := []struct {
		password string
		want     string
	}{
		{password: "1234", want: "2b91-1234~crocau"},
		{password: "password", want: "fe6e-password~crocau"},
		{password: "пароль", want: "a2a8-пароль~crocau"},
	}
	for _, tt := range tests {
		t.Run(tt.password, func(t *testing.T) {
			if got := makeSecret(tt.password); got != tt.want {
				t.Fatalf("makeSecret(%q) = %q, want %q", tt.password, got, tt.want)
			}
		})
	}
}

func TestRelayOrderMatchesWindowsGoldenValues(t *testing.T) {
	got := relayOrder("2b91-1234~crocau", "")
	want := []string{
		"3.getcroc.com:9009",
		"4.getcroc.com:9009",
		"1.getcroc.com:9009",
		"2.getcroc.com:9009",
		"croc.schollz.com:9009",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("relayOrder() = %#v, want %#v", got, want)
	}
}

func TestSelectedRelayOverridesPool(t *testing.T) {
	got := relayOrder("2b91-1234~crocau", " relay.example:9100 ")
	want := []string{"relay.example:9100"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("relayOrder(selected) = %#v, want %#v", got, want)
	}
}

func TestNewTransferSessionParsesJavaBindablePaths(t *testing.T) {
	path := filepath.Join(t.TempDir(), "файл с пробелом.txt")
	if err := os.WriteFile(path, []byte("data"), 0o600); err != nil {
		t.Fatal(err)
	}
	pathsJSON, err := json.Marshal([]string{path})
	if err != nil {
		t.Fatal(err)
	}
	session, err := NewTransferSession(ModeSend, "pw", string(pathsJSON), "", "", "")
	if err != nil {
		t.Fatal(err)
	}
	if session.secret != makeSecret("pw") {
		t.Fatalf("session secret = %q, want the Windows mapping", session.secret)
	}
	if len(session.paths) != 1 || session.paths[0] != path {
		t.Fatalf("session paths = %#v, want %#v", session.paths, []string{path})
	}
	if session.relayPassword != defaultRelayPassphrase {
		t.Fatalf("relay password default = %q, want %q", session.relayPassword, defaultRelayPassphrase)
	}
}

func TestCrocOptionsMatchWindowsBaseline(t *testing.T) {
	sender := &TransferSession{mode: ModeSend, secret: "2b91-1234~crocau", relayPassword: defaultRelayPassphrase}
	sendOptions := crocOptions(sender, "3.getcroc.com:9009")
	if !sendOptions.IsSender || sendOptions.SharedSecret != sender.secret || sendOptions.RelayAddress != "3.getcroc.com:9009" {
		t.Fatalf("unexpected sender identity or relay: %#v", sendOptions)
	}
	if !sendOptions.NoPrompt || !sendOptions.DisableLocal || !sendOptions.IgnoreStdin || sendOptions.Overwrite {
		t.Fatalf("unexpected sender flags: %#v", sendOptions)
	}
	if !reflect.DeepEqual(sendOptions.RelayPorts, []string{"9009", "9010", "9011", "9012", "9013"}) {
		t.Fatalf("unexpected sender ports: %#v", sendOptions.RelayPorts)
	}
	if sendOptions.Curve != "p256" || sendOptions.HashAlgorithm != "xxhash" || sendOptions.RelayPassword != "pass123" {
		t.Fatalf("unexpected Croc defaults: %#v", sendOptions)
	}

	receiver := &TransferSession{mode: ModeReceive, secret: sender.secret, relayPassword: defaultRelayPassphrase}
	receiveOptions := crocOptions(receiver, "3.getcroc.com:9009")
	if receiveOptions.IsSender || !receiveOptions.Overwrite || receiveOptions.IgnoreStdin {
		t.Fatalf("unexpected receiver flags: %#v", receiveOptions)
	}
}

func TestProbeRelayAddressUsesCrocPingPong(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	serverErr := make(chan error, 1)
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			serverErr <- acceptErr
			return
		}
		defer conn.Close()
		channel := comm.New(conn)
		message, receiveErr := channel.Receive()
		if receiveErr != nil {
			serverErr <- receiveErr
			return
		}
		if string(message) != "ping" {
			serverErr <- errors.New("expected ping")
			return
		}
		serverErr <- channel.Send([]byte("pong"))
	}()
	rtt, err := probeRelayAddress(listener.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if rtt < 0 {
		t.Fatalf("negative round-trip time: %s", rtt)
	}
	if err := <-serverErr; err != nil {
		t.Fatal(err)
	}
}

func TestProbeRelayAddressRejectsNonPongResponse(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		channel := comm.New(conn)
		_, _ = channel.Receive()
		_ = channel.Send([]byte("not-pong"))
	}()
	if _, err := probeRelayAddress(listener.Addr().String(), time.Second); err == nil {
		t.Fatal("expected a non-pong relay response to fail")
	}
}
