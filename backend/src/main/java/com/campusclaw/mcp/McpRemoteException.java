package com.campusclaw.mcp;

class McpRemoteException extends RuntimeException {
    McpRemoteException(String message) { super(message); }
    McpRemoteException(String message, Throwable cause) { super(message, cause); }
}
