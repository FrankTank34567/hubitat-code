package groovyx.net.http
class HttpResponseException extends RuntimeException { int statusCode; def response; HttpResponseException(int c){ super("HTTP "+c); statusCode=c } }
