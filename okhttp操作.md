```java
public class HttpUtils {
    private static final Logger logger = LoggerFactory.getLogger(HttpUtils.class);
    public static final String EMPTY_STRING = "";
    public static final Map<String, String> EMPTY_PARAM = new HashMap<>(2);
    public static final MediaType POST_MEDIA_TYPE = MediaType.parse("application/json;charset=UTF-8");

    private static final OkHttpClient client = new OkHttpClient().newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();


    /**
     * GET请求，读取返回的response.body里面的文本
     * @param url
     * @param params
     * @param headers
     * @return
     */
    public static String get(String url, Map<String, String> params, Map<String, String> headers) {
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.addQueryParameter(entry.getKey(), entry.getValue());
        }
        HttpUrl httpUrl = builder.build();

        Request.Builder requestBuilder = new Request.Builder();
        requestBuilder.url(httpUrl);

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        Request request = requestBuilder.build();
        Call call = client.newCall(request);

        try {
            Response response = call.execute();
            if (response.isSuccessful()) {
                logger.info("HTTP GET success, url:{}, params:{}, headers:{}", url, params, headers);
                return response.body().string();
            } else {
                logger.info("HTTP GET failed, url:{}, params:{}, headers:{}, statusCode:{}", url, params, headers, response.code());
            }
        } catch (IOException e) {
            logger.error("HTTP GET error, url:{}, params:{}, headers:{}", url, params, headers, e);
            call.cancel();
        }
        return EMPTY_STRING;
    }

    /**
     * GET请求，读取返回的response.body里面的文本
     * @param url
     * @return
     */
    public static String get(String url) {
        return get(url, EMPTY_PARAM, EMPTY_PARAM);
    }

    /**
     * POST请求，读取返回的response.body里面的文本
     * @param url
     * @param body
     * @param headers
     * @return
     */
    public static String post(String url, Object body, Map<String, String> headers) {
        HttpUrl httpUrl = HttpUrl.parse(url);

        Request.Builder requestBuilder = new Request.Builder();
        requestBuilder.url(httpUrl);

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        Request request = requestBuilder.post(RequestBody.create(POST_MEDIA_TYPE, JSONObject.toJSONString(body))).build();

        Call call = client.newCall(request);
        try {
            Response response = call.execute();
            if (response.isSuccessful()) {
                logger.info("HTTP POST success, url:{}, body:{}, headers:{}", url, body, headers);
                return response.body().string();
            } else {
                logger.info("HTTP POST failed, url:{}, body:{}, headers:{}, statusCode:{}", url, body, headers, response.code());
            }
        } catch (IOException e) {
            logger.error("HTTP POST error, url:{}, body:{}, headers:{}", url, body, headers, e);
            call.cancel();
        }
        return EMPTY_STRING;
    }

    /**
     * POST请求，读取返回的response.body里面的文本
     * @param url
     * @return
     */
    public static String post(String url) {
        return post(url, EMPTY_PARAM, EMPTY_PARAM);
    }

    public static void main(String[] args) {
        Map<String,String> map = new HashMap<>();
        map.put("aaa", "bbb");
        Map<String,String> header = new HashMap<>();
        header.put("Cookie", "jsessionid=abcdef");
        System.out.println(post("http://localhost:8088/?name=tom&value=111", map, header));
    }
}
```
