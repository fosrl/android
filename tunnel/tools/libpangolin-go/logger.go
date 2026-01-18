package main

// #cgo LDFLAGS: -llog
// #include <android/log.h>
import "C"

import (
	"fmt"
	"os"
	"os/signal"
	"time"
	"runtime/debug"
	
	"unsafe"

	"github.com/fosrl/newt/logger"
	"golang.org/x/sys/unix"
)

// Global file logger
var logFile *os.File
var logFilePath string

// Log rotation settings
const (
	maxLogFileSize = 10 * 1024 * 1024 // 10 MB
	maxLogBackups  = 3                // Keep 3 old log files
)

// LogLevel represents the severity of a log message
type LogLevel int

const (
	LogLevelDebug LogLevel = iota
	LogLevelInfo
	LogLevelWarn
	LogLevelError
)

// cstring converts a Go string to a C string using unix.BytePtrFromString
func cstring(s string) *C.char {
	b, err := unix.BytePtrFromString(s)
	if err != nil {
		b := [1]C.char{}
		return &b[0]
	}
	return (*C.char)(unsafe.Pointer(b))
}

// Logger provides formatted logging functionality
type Logger struct {
	prefix   string
	logLevel LogLevel
	tag      *C.char
}

// NewLogger creates a new logger instance
func NewLogger(prefix string) *Logger {
	return &Logger{
		prefix:   prefix,
		logLevel: LogLevelDebug,
		tag:      cstring("GoBackend/" + prefix),
	}
}

// SetLevel sets the minimum log level
func (l *Logger) SetLevel(level LogLevel) {
	l.logLevel = level
}

// GetLevel returns the current log level
func (l *Logger) GetLevel() LogLevel {
	return l.logLevel
}

// formatMessage formats a log message with format string and args
func (l *Logger) formatMessage(format string, args ...interface{}) string {
	if len(args) > 0 {
		return fmt.Sprintf(format, args...)
	}
	return format
}

// logToAndroid sends a log message to Android logcat AND file
func (l *Logger) logToAndroid(level LogLevel, format string, args ...interface{}) {
	if l.logLevel > level {
		return
	}

	message := l.formatMessage(format, args...)

	// Map Go log levels to Android log levels
	var androidLogLevel C.int
	var levelStr string
	switch level {
	case LogLevelDebug:
		androidLogLevel = C.ANDROID_LOG_DEBUG
		levelStr = "DEBUG"
	case LogLevelInfo:
		androidLogLevel = C.ANDROID_LOG_INFO
		levelStr = "INFO"
	case LogLevelWarn:
		androidLogLevel = C.ANDROID_LOG_WARN
		levelStr = "WARN"
	case LogLevelError:
		androidLogLevel = C.ANDROID_LOG_ERROR
		levelStr = "ERROR"
	default:
		androidLogLevel = C.ANDROID_LOG_INFO
		levelStr = "INFO"
	}

	// Log to logcat
	C.__android_log_write(androidLogLevel, l.tag, cstring(message))
	
	// Log to file
	if logFile != nil {
		timestamp := time.Now().Format("2006-01-02 15:04:05.000")
		logLine := fmt.Sprintf("%s [%s] %s: %s\n", timestamp, levelStr, l.prefix, message)
		logFile.WriteString(logLine)
		
		// Check if we need to rotate the log
		checkAndRotateLog()
	}
}

// Debug logs a debug message
func (l *Logger) Debug(format string, args ...interface{}) {
	l.logToAndroid(LogLevelDebug, format, args...)
}

// Info logs an info message
func (l *Logger) Info(format string, args ...interface{}) {
	l.logToAndroid(LogLevelInfo, format, args...)
}

// Warn logs a warning message
func (l *Logger) Warn(format string, args ...interface{}) {
	l.logToAndroid(LogLevelWarn, format, args...)
}

// Error logs an error message
func (l *Logger) Error(format string, args ...interface{}) {
	l.logToAndroid(LogLevelError, format, args...)
}

// AndroidLogWriter adapts our Logger to the newt/logger LogWriter interface
type AndroidLogWriter struct {
	logger *Logger
}

// Write implements the logger.LogWriter interface
func (w *AndroidLogWriter) Write(level logger.LogLevel, timestamp time.Time, message string) {
	// Map newt/logger.LogLevel to our LogLevel
	var ourLevel LogLevel
	switch level {
	case logger.DEBUG:
		ourLevel = LogLevelDebug
	case logger.INFO:
		ourLevel = LogLevelInfo
	case logger.WARN:
		ourLevel = LogLevelWarn
	case logger.ERROR:
		ourLevel = LogLevelError
	default:
		ourLevel = LogLevelInfo
	}

	// Call the appropriate method on our logger
	switch ourLevel {
	case LogLevelDebug:
		w.logger.Debug("%s", message)
	case LogLevelInfo:
		w.logger.Info("%s", message)
	case LogLevelWarn:
		w.logger.Warn("%s", message)
	case LogLevelError:
		w.logger.Error("%s", message)
	}
}

// NewAndroidLogWriter creates a new AndroidLogWriter that wraps our Logger
func NewAndroidLogWriter(logger *Logger) *AndroidLogWriter {
	return &AndroidLogWriter{logger: logger}
}

// global logger instance
var appLogger *Logger

func init() {
	appLogger = NewLogger("PangolinGo")
	// Log level will be set via setLogLevel
	appLogger.Info("Logger initialized")
	
    // This makes unexpected faults (like nil pointer) call panic instead
    // of immediately crashing, giving recover() a chance
    debug.SetPanicOnFault(true)
    
    // Also set crash output
    debug.SetTraceback("all")
    
    // Your existing signal handlers can stay for SIGUSR2
    signals := make(chan os.Signal, 1)
    signal.Notify(signals, unix.SIGUSR2)
    go func() {
        for range signals {
            buf := make([]byte, 1<<20)
            C.__android_log_write(C.ANDROID_LOG_ERROR, cstring("GoBackend/Stacktrace"), (*C.char)(unsafe.Pointer(&buf[0])))
        }
    }()
	
}

// InitFileLogger initializes file logging - call this from Java/Kotlin with the app's file directory
//
//export InitFileLogger
func InitFileLogger(filePath *C.char) {
	goPath := C.GoString(filePath)
	logFilePath = goPath
	
	// Clean up old backup logs on initialization
	cleanupOldBackups()
	
	var err error
	logFile, err = os.OpenFile(goPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err != nil {
		appLogger.Error("Failed to open log file: %v", err)
		return
	}
	appLogger.Info("File logging initialized: %s", goPath)
}

// CloseFileLogger closes the file logger
//
//export CloseFileLogger
func CloseFileLogger() {
	if logFile != nil {
		logFile.Close()
		logFile = nil
	}
}

// checkAndRotateLog checks if the log file exceeds the maximum size and rotates it if needed
func checkAndRotateLog() {
	if logFile == nil || logFilePath == "" {
		return
	}
	
	// Get current file info
	fileInfo, err := logFile.Stat()
	if err != nil {
		return
	}
	
	// Check if rotation is needed
	if fileInfo.Size() < maxLogFileSize {
		return
	}
	
	// Close current log file
	logFile.Close()
	
	// Rotate existing backups (delete oldest if at max)
	for i := maxLogBackups - 1; i >= 1; i-- {
		oldName := fmt.Sprintf("%s.%d", logFilePath, i)
		newName := fmt.Sprintf("%s.%d", logFilePath, i+1)
		
		// Delete the oldest backup if it exists
		if i == maxLogBackups-1 {
			os.Remove(newName)
		}
		
		// Rename if the old backup exists
		if _, err := os.Stat(oldName); err == nil {
			os.Rename(oldName, newName)
		}
	}
	
	// Rename current log to .1
	backupName := fmt.Sprintf("%s.1", logFilePath)
	os.Rename(logFilePath, backupName)
	
	// Create new log file
	var err2 error
	logFile, err2 = os.OpenFile(logFilePath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
	if err2 != nil {
		// If we can't open the new file, try to reopen the backup
		logFile, _ = os.OpenFile(backupName, os.O_APPEND|os.O_WRONLY, 0644)
	}
}

// cleanupOldBackups removes backup log files that exceed maxLogBackups
func cleanupOldBackups() {
	if logFilePath == "" {
		return
	}
	
	// Remove any backups beyond our max
	for i := maxLogBackups + 1; i <= maxLogBackups + 10; i++ {
		backupName := fmt.Sprintf("%s.%d", logFilePath, i)
		os.Remove(backupName)
	}
}

// setLogLevel sets the log level for the Go logger
// level: 0=DEBUG, 1=INFO, 2=WARN, 3=ERROR
//
//export setLogLevel
func setLogLevel(level C.int) {
	appLogger.SetLevel(LogLevel(level))
}

// getCurrentLogLevel returns the current log level from appLogger
func getCurrentLogLevel() LogLevel {
	return appLogger.GetLevel()
}

// logLevelToNewtLoggerLevel converts LogLevel to logger.LogLevel from newt/logger package
func logLevelToNewtLoggerLevel(level LogLevel) logger.LogLevel {
	switch level {
	case LogLevelDebug:
		return logger.DEBUG
	case LogLevelInfo:
		return logger.INFO
	case LogLevelWarn:
		return logger.WARN
	case LogLevelError:
		return logger.ERROR
	default:
		return logger.INFO
	}
}

// logLevelToString converts LogLevel to a string representation
func logLevelToString(level LogLevel) string {
	switch level {
	case LogLevelDebug:
		return "debug"
	case LogLevelInfo:
		return "info"
	case LogLevelWarn:
		return "warn"
	case LogLevelError:
		return "error"
	default:
		return "info"
	}
}

// InitOLMLogger initializes the OLM logger with the current log level from appLogger
func InitOLMLogger() {
	// Create a LogWriter adapter that wraps our appLogger
	androidLogWriter := NewAndroidLogWriter(appLogger)

	// Create a logger instance using the newt/logger package with our custom writer
	olmLogger := logger.NewLoggerWithWriter(androidLogWriter)
	olmLogger.SetLevel(logLevelToNewtLoggerLevel(getCurrentLogLevel()))

	logger.Init(olmLogger)
}

// GetLogLevelString returns the current log level as a string for OLM config
func GetLogLevelString() string {
	return logLevelToString(getCurrentLogLevel())
}
