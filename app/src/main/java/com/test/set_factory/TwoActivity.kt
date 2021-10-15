package com.test.set_factory

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class TwoActivity : AppCompatActivity() {
    companion object {
        private val TAG = "test_two_activity"
    }

    private val handler = MyHandler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_two)
        Log.e(TAG, "onCreate(savedInstanceState: Bundle?)")
        handler.sendEmptyMessage(1024)
    }

    override fun onStart() {
        super.onStart()
        Log.e(TAG, "onStart()")
    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume()")
    }

    private fun log(methodName: String) {
        Log.e(TAG, methodName)
    }

    private class MyHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            Log.e(TAG, "handleMessage")
        }
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        return super.onCreateView(name, context, attrs)
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        return super.onCreateView(parent, name, context, attrs)
    }
}