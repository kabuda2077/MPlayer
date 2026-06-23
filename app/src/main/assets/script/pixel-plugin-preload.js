'use strict'

globalThis.lx_setup = function(key, id, name, description, version, author, homepage, rawScript) {
  delete globalThis.lx_setup

  const nativeCallRaw = globalThis.__pixel_native_call__
  const nativeMd5 = globalThis.__pixel_native_md5__
  const nativeAes = globalThis.__pixel_native_aes_encrypt__
  const nativeRsa = globalThis.__pixel_native_rsa_encrypt__
  const nativeB64 = globalThis.__pixel_native_b64__
  const nativeB64ToBytes = globalThis.__pixel_native_b64_to_bytes__
  const nativeRequest = globalThis.__pixel_native_request__
  const nativeSetTimeout = globalThis.__pixel_native_set_timeout__

  delete globalThis.__pixel_native_call__
  delete globalThis.__pixel_native_md5__
  delete globalThis.__pixel_native_aes_encrypt__
  delete globalThis.__pixel_native_rsa_encrypt__
  delete globalThis.__pixel_native_b64__
  delete globalThis.__pixel_native_b64_to_bytes__
  delete globalThis.__pixel_native_request__
  delete globalThis.__pixel_native_set_timeout__

  const checkLength = (value, max = 2097152) => {
    if (typeof value === 'string' && value.length > max) throw new Error('Input too long')
    return value
  }
  const nativeCall = (action, data) => {
    const json = JSON.stringify(data)
    checkLength(json)
    nativeCallRaw(key, action, json)
  }

  const events = { request: null }
  const callbacks = new Map()
  const httpRequests = new Map()
  let timerId = 1

  const stringToBytes = (input) => {
    const bytes = []
    for (let i = 0; i < input.length; i++) {
      const code = input.charCodeAt(i)
      if (code < 128) bytes.push(code)
      else if (code < 2048) {
        bytes.push((code >> 6) | 192)
        bytes.push((code & 63) | 128)
      } else {
        bytes.push((code >> 12) | 224)
        bytes.push(((code >> 6) & 63) | 128)
        bytes.push((code & 63) | 128)
      }
    }
    return bytes
  }
  const bytesToString = (bytes) => {
    let result = ''
    let i = 0
    while (i < bytes.length) {
      const b = bytes[i]
      if (b < 128) {
        result += String.fromCharCode(b)
        i++
      } else if (b >= 192 && b < 224) {
        result += String.fromCharCode(((b & 31) << 6) | (bytes[i + 1] & 63))
        i += 2
      } else {
        result += String.fromCharCode(((b & 15) << 12) | ((bytes[i + 1] & 63) << 6) | (bytes[i + 2] & 63))
        i += 3
      }
    }
    return result
  }

  const buffer = {
    from(input, encoding) {
      if (typeof input === 'string') {
        switch (encoding) {
          case 'base64': return new Uint8Array(JSON.parse(nativeB64ToBytes(input)))
          case 'hex': return new Uint8Array(input.match(/.{1,2}/g).map(byte => parseInt(byte, 16)))
          default: return new Uint8Array(stringToBytes(input))
        }
      }
      if (Array.isArray(input) || ArrayBuffer.isView(input)) return new Uint8Array(input)
      throw new Error('Unsupported buffer input')
    },
    bufToString(buf, format) {
      if (!Array.isArray(buf) && !ArrayBuffer.isView(buf)) throw new Error('Input is not a buffer')
      const bytes = Array.from(buf)
      switch (format) {
        case 'hex': return bytes.reduce((str, byte) => str + byte.toString(16).padStart(2, '0'), '')
        case 'base64': return nativeB64(bytesToString(bytes))
        case 'binary': return bytes
        case 'utf8':
        case 'utf-8':
        default: return bytesToString(bytes)
      }
    },
  }
  const dataToBase64 = (value) => {
    if (typeof value === 'string') return nativeB64(value)
    if (Array.isArray(value) || ArrayBuffer.isView(value)) return nativeB64(buffer.bufToString(value, 'utf8'))
    throw new Error('Unsupported crypto input')
  }

  const utils = {
    crypto: {
      md5(str) {
        if (typeof str !== 'string') throw new Error('param required a string')
        return nativeMd5(encodeURIComponent(str))
      },
      aesEncrypt(input, mode, aesKey, iv) {
        const javaMode = mode === 'aes-128-ecb' ? 'AES/ECB/NoPadding' : 'AES/CBC/PKCS5Padding'
        return buffer.from(nativeAes(dataToBase64(input), dataToBase64(aesKey), iv ? dataToBase64(iv) : '', javaMode), 'base64')
      },
      rsaEncrypt(input, publicKey) {
        const keyBody = String(publicKey)
          .replace('-----BEGIN PUBLIC KEY-----', '')
          .replace('-----END PUBLIC KEY-----', '')
          .replace(/\s+/g, '')
        return buffer.from(nativeRsa(dataToBase64(input), keyBody, 'RSA/ECB/NoPadding'), 'base64')
      },
      randomBytes(size) {
        const bytes = new Uint8Array(size)
        for (let i = 0; i < size; i++) bytes[i] = Math.floor(Math.random() * 256)
        return bytes
      },
    },
    buffer,
  }

  const handleHttpResponse = ({ requestKey, error, response }) => {
    const target = httpRequests.get(requestKey)
    if (!target) return
    httpRequests.delete(requestKey)
    if (error == null) {
      target.callback(null, {
        statusCode: response.statusCode,
        statusMessage: response.statusMessage,
        headers: response.headers,
        body: response.body,
      }, response.body)
    } else {
      target.callback(new Error(error), null, null)
    }
  }

  const handlePluginRequest = ({ requestKey, data }) => {
    if (typeof events.request !== 'function') {
      nativeCall('response', { requestKey, status: false, errorMessage: 'Request event is not defined' })
      return
    }
    try {
      Promise.resolve(events.request.call(globalThis.lx, data))
        .then((result) => {
          nativeCall('response', { requestKey, status: true, result })
        })
        .catch((error) => {
          nativeCall('response', { requestKey, status: false, errorMessage: error && error.message ? error.message : String(error) })
        })
    } catch (error) {
      nativeCall('response', { requestKey, status: false, errorMessage: error && error.message ? error.message : String(error) })
    }
  }

  Object.defineProperty(globalThis, '__pixel_native__', {
    enumerable: false,
    configurable: false,
    writable: false,
    value: function(_key, action, data) {
      if (_key !== key) return 'Invalid key'
      const parsed = data == null ? null : JSON.parse(data)
      switch (action) {
        case 'request': return handlePluginRequest(parsed)
        case 'httpResponse': return handleHttpResponse(parsed)
        case 'timeout': {
          const callback = callbacks.get(parsed)
          if (!callback) return
          callbacks.delete(parsed)
          callback()
          return
        }
        default: return 'Unknown action: ' + action
      }
    },
  })

  globalThis.lx = {
    EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },
    request(url, options, callback) {
      if (typeof options === 'function') {
        callback = options
        options = {}
      }
      options = options || {}
      if (typeof callback !== 'function') throw new Error('callback required')
      const requestKey = Math.random().toString(36).slice(2)
      httpRequests.set(requestKey, { callback })
      nativeRequest(key, JSON.stringify({ requestKey, url, options }))
      return () => {
        httpRequests.delete(requestKey)
        nativeCall('cancelRequest', { requestKey })
      }
    },
    send(eventName, data) {
      if (eventName === 'inited') {
        nativeCall('init', { status: true, info: data })
        return Promise.resolve()
      }
      return Promise.reject(new Error('The event is not supported: ' + eventName))
    },
    on(eventName, handler) {
      if (eventName !== 'request') return Promise.reject(new Error('The event is not supported: ' + eventName))
      events.request = handler
      return Promise.resolve()
    },
    utils,
    currentScriptInfo: { id, name, description, version, author, homepage, rawScript },
    version: '2.0.0',
    env: 'mobile',
  }

  globalThis.setTimeout = function(callback, timeout) {
    if (typeof callback !== 'function') throw new Error('callback required')
    const id = timerId++
    callbacks.set(id, callback)
    nativeSetTimeout(key, id, Math.max(0, parseInt(timeout || 0)))
    return id
  }
  globalThis.clearTimeout = function(id) {
    callbacks.delete(id)
  }
  globalThis.eval = function() {
    throw new Error('eval is not available')
  }
  const proxyFunctionConstructor = new Proxy(Function.prototype.constructor, {
    apply() { throw new Error('Dynamic code execution is not allowed.') },
    construct() { throw new Error('Dynamic code execution is not allowed.') },
  })
  Object.defineProperty(Function.prototype, 'constructor', {
    value: proxyFunctionConstructor,
    writable: false,
    configurable: false,
    enumerable: false,
  })
  globalThis.Function = proxyFunctionConstructor
}
