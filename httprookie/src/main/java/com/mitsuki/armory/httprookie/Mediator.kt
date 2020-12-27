package com.mitsuki.armory.httprookie

import com.mitsuki.armory.httprookie.callback.Callback
import com.mitsuki.armory.httprookie.request.Request
import com.mitsuki.armory.httprookie.response.Response
import okhttp3.Call
import java.io.IOException


class Mediator<T>(private val mRequest: Request<T>) : Cloneable {

    private var mCallback: Callback<T>? = null
    private lateinit var mRawCall: Call

    @Volatile
    private var isCanceled = false
    private var isExecuted = false

    @Synchronized
    private fun prepareRawCall(): Call {
        if (isExecuted) throw java.lang.RuntimeException("")
        isExecuted = true
        mRawCall = mRequest.generateCall()
        if (isCanceled) mRawCall.cancel()
        return mRawCall
    }

    fun execute(cb: Callback<T>) {
        this.mCallback = cb
        this.mCallback?.onStart()
        prepareRawCall().enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                error { Response.Fail(e, call, null) }
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                val code = response.code
                if (code == 404 || code >= 500) {
                    error { Response.Fail(RuntimeException("404 or 50x"), call, response) }
                    return
                }
                try {
                    mRequest.convert.convertResponse(response).apply {
                        success { Response.Success(this, call, response) }
                    }
                } catch (e: Exception) {
                    error { Response.Fail(e, call, response) }
                }
            }

        })
    }

    fun execute(): Response<T?> {
        try {
            val response: okhttp3.Response = prepareRawCall().execute()
            val code = response.code
            if (code == 404 || code >= 500) {
                return Response.Fail(RuntimeException("404 or 50x"), mRawCall, response)
            }

            return mRequest.convert.convertResponse(response).run {
                Response.Success(this, mRawCall, response)
            }
        } catch (throwable: Throwable) {
            return Response.Fail(throwable, mRawCall, null)
        }
    }

    fun cancel() {
        isCanceled = true
        if (this::mRawCall.isInitialized) {
            mRawCall.cancel()
        }
    }

    fun isCanceled(): Boolean {
        if (isCanceled) return true
        synchronized(this) { return this::mRawCall.isInitialized && mRawCall.isCanceled() }
    }

    private inline fun error(func: () -> Response.Fail<T?>) {
        mCallback?.onError(func())
        mCallback?.onFinish()
    }

    private inline fun success(func: () -> Response.Success<T?>) {
        mCallback?.onSuccess(func())
        mCallback?.onFinish()
    }

    public override fun clone() = Mediator(mRequest)
}