package com.exkmptracker.ports

/** String-based bridge for Swift hosts; JSON contains business parameters/results only. */
interface TrackingRpcClient {
    /** Return a cancellation handle for the existing host connection's request. */
    fun call(method: String, paramsJson: String, completion: (String?, TrackingRpcFailure?) -> Unit): TrackingRpcCall
    fun notify(method: String, paramsJson: String)
}

interface TrackingRpcCall {
    fun cancel()
}

/** Do not forward server error data or tokens into tracker diagnostics. */
class TrackingRpcFailure(val code: Int)
