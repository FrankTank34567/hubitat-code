package groovyx.net.http
class HttpResponseException extends Exception {
    int statusCode; def response
    HttpResponseException(int code, String msg) { super(msg); statusCode = code; response = [status: code] }
}
