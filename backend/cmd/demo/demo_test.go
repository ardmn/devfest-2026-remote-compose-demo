package main

import (
	"errors"
	"io"
	"os/exec"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

// fakeEnv is a machine with exactly the given environment variables, programs on the PATH and files.
func fakeEnv(goos string, vars map[string]string, onPath []string, files ...string) env {
	return env{
		goos:   goos,
		home:   "/home/me",
		getenv: func(k string) string { return vars[k] },
		lookPath: func(name string) (string, error) {
			if slices.Contains(onPath, name) {
				return "/usr/bin/" + name, nil
			}
			return "", exec.ErrNotFound
		},
		exists: func(p string) bool {
			for _, f := range files {
				if filepath.ToSlash(p) == filepath.ToSlash(f) {
					return true
				}
			}
			return false
		},
		repo: "..",
	}
}

func TestJavaHome(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name string
		env  env
		want string
		err  error
	}{
		{"JAVA_HOME set", fakeEnv("linux", map[string]string{"JAVA_HOME": "/jdk"}, nil), "", nil},
		{"java on the PATH", fakeEnv("linux", nil, []string{"java"}), "", nil},
		{
			"Android Studio on macOS",
			fakeEnv("darwin", nil, nil, "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"),
			"/Applications/Android Studio.app/Contents/jbr/Contents/Home", nil,
		},
		{
			"Android Studio on Windows",
			fakeEnv("windows", map[string]string{"ProgramFiles": "ProgramFiles"}, nil, "ProgramFiles/Android/Android Studio/jbr/bin/java.exe"),
			"ProgramFiles/Android/Android Studio/jbr", nil,
		},
		{"Android Studio on Linux", fakeEnv("linux", nil, nil, "/opt/android-studio/jbr/bin/java"), "/opt/android-studio/jbr", nil},
		{"nothing", fakeEnv("linux", nil, nil), "", errNoJava},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			got, err := tt.env.javaHome()
			if filepath.ToSlash(got) != filepath.ToSlash(tt.want) || !errors.Is(err, tt.err) {
				t.Errorf("javaHome() = %q, %v; want %q, %v", got, err, tt.want, tt.err)
			}
		})
	}
}

func TestADB(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name string
		env  env
		want string
	}{
		{"on the PATH", fakeEnv("darwin", nil, []string{"adb"}), "/usr/bin/adb"},
		{"ANDROID_HOME", fakeEnv("linux", map[string]string{"ANDROID_HOME": "/sdk"}, nil, "/sdk/platform-tools/adb"), "/sdk/platform-tools/adb"},
		{"the macOS default SDK", fakeEnv("darwin", nil, nil, "/home/me/Library/Android/sdk/platform-tools/adb"), "/home/me/Library/Android/sdk/platform-tools/adb"},
		{
			"the Windows default SDK",
			fakeEnv("windows", map[string]string{"LOCALAPPDATA": `C:/Users/me/AppData/Local`}, nil, `C:/Users/me/AppData/Local/Android/Sdk/platform-tools/adb.exe`),
			`C:/Users/me/AppData/Local/Android/Sdk/platform-tools/adb.exe`,
		},
		{"none", fakeEnv("linux", nil, nil), ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			got, ok := tt.env.adb()
			if filepath.ToSlash(got) != tt.want || ok != (tt.want != "") {
				t.Errorf("adb() = %q, %v; want %q", got, ok, tt.want)
			}
		})
	}
}

func TestToolNamesPerOS(t *testing.T) {
	t.Parallel()
	win, unix := fakeEnv("windows", nil, nil), fakeEnv("darwin", nil, nil)
	for got, want := range map[string]string{
		win.gradlew():  "../tools/gradlew.bat",
		unix.gradlew(): "../tools/gradlew",
		win.rcTool():   "../tools/build/install/rc/bin/rc.bat",
		unix.rcTool():  "../tools/build/install/rc/bin/rc",
	} {
		if filepath.ToSlash(got) != want {
			t.Errorf("got %q, want %q", got, want)
		}
	}
}

func TestIsV2(t *testing.T) {
	t.Parallel()
	for out, want := range map[string]bool{
		"golangci-lint has version 2.15.0 built with go1.27.2": true,
		"golangci-lint has version v2.1.6 built with go1.24":   false, // a "v" prefix: not how v2 prints it
		"golangci-lint has version 1.64.8 built with go1.24.1": false,
		"": false,
	} {
		if got := isV2(out); got != want {
			t.Errorf("isV2(%q) = %v", out, got)
		}
	}
}

func TestUsage(t *testing.T) {
	t.Parallel()
	for _, args := range [][]string{nil, {"deploy"}, {""}} {
		if err := run(t.Context(), args, io.Discard); !errors.Is(err, errUsage) {
			t.Errorf("run(%q) = %v, want the usage", args, err)
		}
	}
	if err := run(t.Context(), []string{"serve", "-nope"}, io.Discard); err == nil {
		t.Error("serve accepted an unknown flag")
	}
	if err := run(t.Context(), []string{"lint", "-nope"}, io.Discard); err == nil {
		t.Error("lint accepted an unknown flag")
	}
}

// lint formats in place for a developer; -check (CI) only reports a diff and fails. The rest is the same.
func TestLintSteps(t *testing.T) {
	t.Parallel()
	local, check := lintSteps("gl", false), lintSteps("gl", true)
	if got := strings.Join(local[0], " "); got != "gl fmt" {
		t.Errorf("format step = %q, want %q", got, "gl fmt")
	}
	if got := strings.Join(check[0], " "); got != "gl fmt --diff" {
		t.Errorf("-check format step = %q, want %q", got, "gl fmt --diff")
	}
	want := []string{"gl run", "go vet ./...", "go test -race -count=1 ./..."}
	for name, steps := range map[string][][]string{"lint": local, "lint -check": check} {
		var rest []string
		for _, s := range steps[1:] {
			rest = append(rest, strings.Join(s, " "))
		}
		if strings.Join(rest, "; ") != strings.Join(want, "; ") {
			t.Errorf("%s runs %q after formatting, want %q", name, rest, want)
		}
	}
}

func TestRunToolReportsTheCommand(t *testing.T) {
	t.Parallel()
	err := runTool(t.Context(), io.Discard, ".", "", filepath.Join(t.TempDir(), "missing"), "arg")
	if err == nil {
		t.Fatal("a missing program ran")
	}
}
