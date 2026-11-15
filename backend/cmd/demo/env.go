package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

// env is what the commands need from the machine: where things are, how to look them up. Tests replace it.
type env struct {
	goos     string
	home     string
	getenv   func(string) string
	lookPath func(string) (string, error)
	exists   func(string) bool
	repo     string // the repository root (backend/..)
}

func realEnv() env {
	home, _ := os.UserHomeDir()
	return env{
		goos:     runtime.GOOS,
		home:     home,
		getenv:   os.Getenv,
		lookPath: exec.LookPath,
		exists:   func(p string) bool { _, err := os.Stat(p); return err == nil },
		repo:     "..",
	}
}

func (e env) exe(name string) string {
	if e.goos == "windows" {
		return name + ".exe"
	}
	return name
}

// gradlew is the tools/ Gradle wrapper for this OS.
func (e env) gradlew() string {
	if e.goos == "windows" {
		return filepath.Join(e.repo, "tools", "gradlew.bat")
	}
	return filepath.Join(e.repo, "tools", "gradlew")
}

// rcTool is the rc tool tools/ builds (gradlew installDist).
func (e env) rcTool() string {
	name := "rc"
	if e.goos == "windows" {
		name = "rc.bat"
	}
	return filepath.Join(e.repo, "tools", "build", "install", "rc", "bin", name)
}

var errNoJava = errors.New("no Java found: install a JDK 17+ (or Android Studio, which bundles one), or set JAVA_HOME")

// javaHome is the JAVA_HOME Gradle should get: "" when JAVA_HOME is set or java is on the PATH already, else the JDK
// bundled with Android Studio.
func (e env) javaHome() (string, error) {
	if e.getenv("JAVA_HOME") != "" {
		return "", nil
	}
	if _, err := e.lookPath("java"); err == nil {
		return "", nil
	}
	var candidates []string
	switch e.goos {
	case "darwin":
		candidates = []string{
			"/Applications/Android Studio.app/Contents/jbr/Contents/Home",
			filepath.Join(e.home, "Applications", "Android Studio.app", "Contents", "jbr", "Contents", "Home"),
		}
	case "windows":
		for _, root := range []string{e.getenv("ProgramFiles"), filepath.Join(e.getenv("LOCALAPPDATA"), "Programs")} {
			if root != "" {
				candidates = append(candidates, filepath.Join(root, "Android", "Android Studio", "jbr"), filepath.Join(root, "Android Studio", "jbr"))
			}
		}
	default:
		candidates = []string{"/opt/android-studio/jbr", filepath.Join(e.home, "android-studio", "jbr"), "/snap/android-studio/current/jbr"}
	}
	for _, c := range candidates {
		if e.exists(filepath.Join(c, "bin", e.exe("java"))) {
			return c, nil
		}
	}
	return "", errNoJava
}

// adb is the Android SDK's adb: on the PATH, else in the SDK (ANDROID_HOME, ANDROID_SDK_ROOT, the usual places).
func (e env) adb() (string, bool) {
	if p, err := e.lookPath("adb"); err == nil {
		return p, true
	}
	sdks := []string{e.getenv("ANDROID_HOME"), e.getenv("ANDROID_SDK_ROOT")}
	switch e.goos {
	case "darwin":
		sdks = append(sdks, filepath.Join(e.home, "Library", "Android", "sdk"))
	case "windows":
		sdks = append(sdks, filepath.Join(e.getenv("LOCALAPPDATA"), "Android", "Sdk"))
	default:
		sdks = append(sdks, filepath.Join(e.home, "Android", "Sdk"))
	}
	for _, sdk := range sdks {
		if sdk == "" {
			continue
		}
		if p := filepath.Join(sdk, "platform-tools", e.exe("adb")); e.exists(p) {
			return p, true
		}
	}
	return "", false
}

// runTool runs name with args in dir — with JAVA_HOME when javaHome is set — and its output going to out. Ctrl+C
// (ctx) stops it.
func runTool(ctx context.Context, out io.Writer, dir, javaHome, name string, args ...string) error {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.Dir = dir
	cmd.Stdout, cmd.Stderr = out, out
	cmd.WaitDelay = 5 * time.Second
	if javaHome != "" {
		cmd.Env = append(os.Environ(), "JAVA_HOME="+javaHome)
	}
	if err := cmd.Run(); err != nil {
		return fmt.Errorf("%s: %w", strings.Join(cmd.Args, " "), err)
	}
	return nil
}
