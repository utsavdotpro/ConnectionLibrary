package com.isolpro.library.connection

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.isolpro.custom.Callback
import com.isolpro.library.connection.helpers.Utils
import com.isolpro.library.connection.interfaces.ResponseParser
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.concurrent.Executors

abstract class Connection<T> {
  private val REQUST_MODE_POST = "POST";
  private val REQUST_MODE_GET = "GET";

  private val mExecutor: Executor = Executors.newSingleThreadExecutor()
  private val handler = Handler(Looper.getMainLooper())

  protected abstract var config: Config;

  private var payload: Any? = JSONObject()
  private var success: Callback<T>? = null
  private var failure: Callback<T>? = null
  private var parser: ResponseParser<T> = DefaultResponseParser()
  private var loader: Boolean = true
  private var useCacheFirst: Boolean = false

  private var endpoint: String = ""
  private var requestMode: String = REQUST_MODE_POST

  private var offlineEndpoint: String? = ""

  // Public Methods

  fun endpoint(endpoint: String): Connection<T> {
    this.endpoint = endpoint
    return this;
  }

  fun offlineEndpoint(offlineEndpoint: String, uniqueRowId: String? = ""): Connection<T> {
    this.offlineEndpoint = offlineEndpoint + uniqueRowId
    return this;
  }

  fun payload(payload: Any?): Connection<T> {
    this.payload = payload;
    return this;
  }

  fun config(config: Config): Connection<T> {
    this.config = config;
    return this;
  }

  fun loader(loader: Boolean): Connection<T> {
    this.loader = loader;
    return this;
  }

  fun useCacheFirst(useCacheFirst: Boolean): Connection<T> {
    this.useCacheFirst = useCacheFirst;
    return this
  }

  fun post() {
    this.requestMode = REQUST_MODE_POST;
    execute()
  }

  // Event Methods

  private fun onRequestCreated(endpoint: String, data: Any?) {
    handleOnRequestCreated(endpoint, data);
  }

  private fun onShowLoader() {
    if (loader) showLoader()
  }

  private fun onHideLoader() {
    if (loader) hideLoader()
  }

  protected open fun onSuccess(res: T?) {
    success?.exec(res)
    onHideLoader()
  }

  protected open fun onFailure() {
    failure?.exec(null)
    onHideLoader()
  }

  private fun onResponseReceived(data: String?) {
    handler.post {
      handleOnResponseReceived(data);
    }
  }

  private fun onNoResponseError() {
    handler.post {
      handleOnNoResponseError();
    }
  }

  private fun onOfflineDataUnsupported() {
    handler.post {
      handleOnOfflineDataUnsupported()
      onHideLoader()
    }
  }

  private fun onOfflineDataUnavailable() {
    handler.post {
      handleOnOfflineDataUnavailable()
      onHideLoader()
    }
  }

  private fun onError(e: Exception) {
    handler.post {
      handleOnError(e);
      onHideLoader()
    }
  }

  private fun execute() {
    if (loader) onShowLoader()

    payload = mutateRequest(payload);

    // not connected to internet, use offline data
    if (isOfflineMode()) {
      this.doInBackgroundOffline()
      return;
    }

    // connected to internet but using cache first strategy
    if (useCacheFirst) {
      // send offline data but silent any error or callbacks
      this.doInBackgroundOffline(true)
    }

    mExecutor.execute { this.doInBackground() }
  }

  fun success(success: Callback<T>): Connection<T> {
    this.success = success;
    return this;
  }

  fun failure(failure: Callback<T>): Connection<T> {
    this.failure = failure;
    return this;
  }

  fun parser(parser: ResponseParser<T>): Connection<T> {
    this.parser = parser;
    return this;
  }

  private fun hasOfflineEndpoint(): Boolean {
    return offlineEndpoint != null && offlineEndpoint != ""
  }

  private fun doInBackground() {
    try {
      val apiEndpoint = config.baseEndpoint + endpoint;
      val apiURL = URL(apiEndpoint)

      onRequestCreated(apiEndpoint, payload);

      val httpURLConnection = apiURL.openConnection() as HttpURLConnection
      httpURLConnection.requestMethod = requestMode
      httpURLConnection.doOutput = true
      httpURLConnection.doInput = true

      val outputStream = httpURLConnection.outputStream

      val bufferedWriter = BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8))
      bufferedWriter.write(Gson().toJson(payload))
      bufferedWriter.flush()
      bufferedWriter.close()

      outputStream.close()

      val inputStream = httpURLConnection.inputStream

      val bufferedReader = BufferedReader(InputStreamReader(inputStream, "ISO_8859_1"))

      val response = StringBuilder()

      var line: String?

      while (bufferedReader.readLine().also { line = it } != null) {
        response.append(line)
      }

      bufferedReader.close()
      inputStream.close()
      httpURLConnection.disconnect()

      Log.e("Response: ", response.toString());

      handler.post { onPostExecute(response.toString()) }
    } catch (e: IOException) {
      onError(e)
    } catch (e: JSONException) {
      onError(e)
    }
  }

  private fun doInBackgroundOffline(silentMode: Boolean = false) {
    if (!hasOfflineEndpoint()) {
      if (!silentMode)
        onOfflineDataUnsupported()
      return
    }

    if (!silentMode)
      onRequestCreated("offline://$offlineEndpoint", payload)

    try {
      // Read data from file here
      val response: String? = offlineEndpoint?.let { Utils.readFromFile(getContext(), it) }

      if (response === null || response == "") {
        if (!silentMode)
          onOfflineDataUnavailable()
        return
      }

      handler.post { onPostExecute(response) }
    } catch (e: IOException) {
      if (!silentMode) {
        onError(e)
        onOfflineDataUnavailable()
      }
    }
  }

  private fun onPostExecute(responseString: String?) {
    if (responseString.isNullOrEmpty()) {
      onNoResponseError()
      return
    }

    if (!isOfflineMode() && hasOfflineEndpoint()) {
      try {
        offlineEndpoint?.let { Utils.writeToFile(getContext(), it, responseString) }
      } catch (e: IOException) {
        Log.e("IOException", "Failed to save offline data!")
        onError(e)
      }
    }

    val mutatedResponseString = mutateResponse(responseString);

    if (mutatedResponseString.isNullOrEmpty()) {
      onFailure()
      return
    }

    onResponseReceived(mutatedResponseString)

    try {
      val response: T? = parser.parse(mutatedResponseString)

      if (response == null) {
        onFailure();
        return
      }

      handleOnResponseGenerated(response);
      onSuccess(response)
    } catch (e: JSONException) {
      onError(e)
      onFailure()
    }
  }

  private fun isOfflineMode(): Boolean {
    return !Utils.isOnline(getContext())
  }

  // Callbacks

  abstract fun getContext(): Context

  abstract fun showLoader()

  abstract fun hideLoader()

  abstract fun mutateRequest(payload: Any?): Any?

  abstract fun handleOnRequestCreated(endpoint: String, data: Any?)

  abstract fun mutateResponse(data: String?): String?

  abstract fun handleOnResponseReceived(data: String?)

  abstract fun handleOnResponseGenerated(data: T?)

  abstract fun handleOnNoResponseError()

  abstract fun handleOnOfflineDataUnsupported()

  abstract fun handleOnOfflineDataUnavailable()

  abstract fun handleOnError(e: Exception)

  class Config(val baseEndpoint: String)
}