package main

// #cgo LDFLAGS: -llog
// #include <android/log.h>
import "C"

import (
	"fmt"
	"os"
	"os/signal"
	"sync"
	"time"
	"runtime/debug"
	
	"unsafe"

	"github.com/fosrl/newt/logger"
	"golang.org/x/sys/unix"
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
	prefix      string
	logLevel    LogLevel
	tag         *C.char
	logFile     *os.File
	logFileMux  sync.Mutex
}

// NewLogger creates a new logger instance
func NewLogger(prefix string) *Logger {
	return &Logger{
		prefix:   prefix,
		logLevel: LogLevelDebug,
		tag:      cstring("GoBackend/" + prefix),
		logFile:  nil,
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

// logToFile writes a log message to the file if file logging is enabled
func (l *Logger) logToFile(level LogLevel, message string) {
	l.logFileMux.Lock()
	defer l.logFileMux.Unlock()

	if l.logFile == nil {
		return
	}

	// Get level string
	levelStr := ""
	switch level {
	case LogLevelDebug:
		levelStr = "DEBUG"
	case LogLevelInfo:
		levelStr = "INFO"
	case LogLevelWarn:
		levelStr = "WARN"
	case LogLevelError:
		levelStr = "ERROR"
	}

	// Format: timestamp [LEVEL] prefix: message
	timestamp := time.Now().Format("2006-01-02 15:04:05.000")
	logLine := fmt.Sprintf("%s [%s] %s: %s\n", timestamp, levelStr, l.prefix, message)
	
	l.logFile.WriteString(logLine)
}

// logToAndroid sends a log message to Android logcat
func (l *Logger) logToAndroid(level LogLevel, format string, args ...interface{}) {
	if l.logLevel > level {
		return
	}

	message := l.formatMessage(format, args...)

	// Also log to file if enabled
	l.logToFile(level, message)

	// Map Go log levels to Android log levels
	var androidLogLevel C.int
	switch level {
	case LogLevelDebug:
		androidLogLevel = C.ANDROID_LOG_DEBUG
	case LogLevelInfo:
		androidLogLevel = C.ANDROID_LOG_INFO
	case LogLevelWarn:
		androidLogLevel = C.ANDROID_LOG_WARN
	case LogLevelError:
		androidLogLevel = C.ANDROID_LOG_ERROR
	default:
		androidLogLevel = C.ANDROID_LOG_INFO
	}

	C.__android_log_write(androidLogLevel, l.tag, cstring(message))
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

// enableFileLogging enables logging to a file at the specified path
// Call this from Android/Kotlin with a path in the app's files directory
//
//export enableFileLogging
func enableFileLogging(filePath *C.char) *C.char {
	path := C.GoString(filePath)
	
	appLogger.logFileMux.Lock()
	defer appLogger.logFileMux.Unlock()
	
	// Close existing file if any
	if appLogger.logFile != nil {
		appLogger.logFile.Close()
	}
	
	// Open new log file (create or append)
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		errMsg := fmt.Sprintf("Failed to open log file: %v", err)
		appLogger.logToAndroid(LogLevelError, errMsg)
		return C.CString(fmt.Sprintf("Error: %s", errMsg))
	}
	
	appLogger.logFile = file
	
	// Write a header to indicate new session
	timestamp := time.Now().Format("2006-01-02 15:04:05.000")
	header := fmt.Sprintf("\n========== Log Session Started: %s ==========\n", timestamp)
	file.WriteString(header)
	
	appLogger.logToAndroid(LogLevelInfo, "File logging enabled: %s", path)
	return C.CString(fmt.Sprintf("File logging enabled: %s", path))
}

// disableFileLogging disables logging to file and closes the file
//
//export disableFileLogging
func disableFileLogging() *C.char {
	appLogger.logFileMux.Lock()
	defer appLogger.logFileMux.Unlock()
	
	if appLogger.logFile != nil {
		// Write footer before closing
		timestamp := time.Now().Format("2006-01-02 15:04:05.000")
		footer := fmt.Sprintf("========== Log Session Ended: %s ==========\n\n", timestamp)
		appLogger.logFile.WriteString(footer)
		
		appLogger.logFile.Close()
		appLogger.logFile = nil
		appLogger.logToAndroid(LogLevelInfo, "File logging disabled")
		return C.CString("File logging disabled")
	}
	
	return C.CString("File logging was not enabled")
}
