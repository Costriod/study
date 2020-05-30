```java
import com.alibaba.fastjson.JSONObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 如果经过了权威证书颁发机构CA的认证https站点，则可以和http方式一样直接调用，如果是自定义的ca证书，则需要自己适配
 */
public class OkHttpUtils {
    private static final Logger logger = LoggerFactory.getLogger(OkHttpUtils.class);
    public static final Map<String, String> EMPTY_PARAM = Collections.emptyMap();
    public static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json;charset=UTF-8");
    public static final MediaType BINARY_MEDIA_TYPE = MediaType.parse("application/octet-stream");

    private static final OkHttpClient client = new OkHttpClient().newBuilder()
            .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    /**
     * GET请求，读取返回的response.body里面的字节数据
     *
     * @param url
     * @param params
     * @param headers 请求头，注意里面不能设置content-type（没啥意义）
     * @return
     */
    public static byte[] get(String url, Map<String, String> params, Map<String, String> headers) {
        HttpUrl.Builder builder = HttpUrl.parse(url).newBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.addEncodedQueryParameter(entry.getKey(), entry.getValue());
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
                logger.info("http get success, url:{}, params:{}, headers:{}", url, params, headers);
                return response.body().bytes();
            } else {
                logger.info("http get failed, url:{}, params:{}, headers:{}, statusCode:{}", url, params, headers, response.code());
                throw new IllegalStateException("http get failed, statusCode: " + response.code() + ", message: " + response.message());
            }
        } catch (IOException e) {
            logger.error("http get error, url:{}, params:{}, headers:{}", url, params, headers, e);
            call.cancel();
            throw new IllegalStateException("http get error: " + e.getMessage());
        }
    }

    /**
     * GET请求，读取返回的response.body里面的字节数据
     *
     * @param url
     * @return
     */
    public static byte[] get(String url) {
        return get(url, EMPTY_PARAM, EMPTY_PARAM);
    }

    /**
     * 注意：本方法post的是application/json格式的报文（不能用于post文件）
     * POST请求，读取返回的response.body里面的字节数据
     *
     * @param url
     * @param body
     * @param headers 请求头，注意里面不能设置content-type（因为是固定的application/json）
     * @return
     */
    public static byte[] postJson(String url, Object body, Map<String, String> headers) {
        HttpUrl httpUrl = HttpUrl.parse(url);

        Request.Builder requestBuilder = new Request.Builder();
        requestBuilder.url(httpUrl);

        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        Request request = requestBuilder.post(RequestBody.create(JSONObject.toJSONString(body), JSON_MEDIA_TYPE)).build();

        Call call = client.newCall(request);
        try {
            Response response = call.execute();
            if (response.isSuccessful()) {
                logger.info("http post success, url:{}, body:{}, headers:{}", url, body, headers);
                return response.body().bytes();
            } else {
                logger.info("http post failed, url:{}, body:{}, headers:{}, statusCode:{}", url, body, headers, response.code());
                throw new IllegalStateException("http post failed, statusCode: " + response.code() + ", message: " + response.message());
            }
        } catch (IOException e) {
            logger.error("http post error, url:{}, body:{}, headers:{}", url, body, headers, e);
            call.cancel();
            throw new IllegalStateException("http post error: " + e.getMessage());
        }
    }

    /**
     * 注意：本方法post的是application/x-www-form-urlencoded格式的报文（不能用于post文件）
     * POST请求，读取返回的response.body里面的字节数据
     *
     * @param url
     * @param body
     * @param headers 请求头，注意里面不能设置content-type（因为是固定的application/x-www-form-urlencoded）
     * @return
     */
    public static byte[] postUrlEncoded(String url, Map<String, String> body, Map<String, String> headers) {
        HttpUrl httpUrl = HttpUrl.parse(url);
        Request.Builder requestBuilder = new Request.Builder();
        requestBuilder.url(httpUrl);
        if (headers == null) {
            headers = EMPTY_PARAM;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        FormBody.Builder requestBodyBuilder = new FormBody.Builder();
        if (body == null) {
            body = EMPTY_PARAM;
        }
        for (Map.Entry<String, String> entry : body.entrySet()) {
            requestBodyBuilder.addEncoded(entry.getKey(), entry.getValue());
        }
        Request request = requestBuilder.post(requestBodyBuilder.build()).build();

        Call call = client.newCall(request);
        try {
            Response response = call.execute();
            if (response.isSuccessful()) {
                logger.info("http post success, url:{}, body:{}, headers:{}", url, body, headers);
                return response.body().bytes();
            } else {
                logger.info("http post failed, url:{}, body:{}, headers:{}, statusCode:{}", url, body, headers, response.code());
                throw new IllegalStateException("http post failed, statusCode: " + response.code() + ", message: " + response.message());
            }
        } catch (IOException e) {
            logger.error("http post error, url:{}, body:{}, headers:{}", url, body, headers, e);
            call.cancel();
            throw new IllegalStateException("http post error: " + e.getMessage());
        }
    }


    /**
     * POST请求，读取返回的response.body里面的字节数据
     *
     * @param url
     * @return
     */
    public static byte[] postJson(String url) {
        return postJson(url, EMPTY_PARAM, EMPTY_PARAM);
    }

    /**
     * POST请求，读取返回的response.body里面的字节数据
     *
     * @param url
     * @return
     */
    public static byte[] postJson(String url, Object body) {
        return postJson(url, body, EMPTY_PARAM);
    }

    /**
     * POST请求，读取返回的response.body里面的字节数据
     *
     * @param url
     * @return
     */
    public static byte[] postUrlEncoded(String url) {
        return postUrlEncoded(url, EMPTY_PARAM, EMPTY_PARAM);
    }

    /**
     * POST请求，读取返回的response.body里面的字节数据
     *
     * @param url
     * @return
     */
    public static byte[] postUrlEncoded(String url, Map<String, String> body) {
        return postUrlEncoded(url, body, EMPTY_PARAM);
    }

    /**
     * 上传文件，支持上传多个文件
     *
     * @param url           地址
     * @param fileParamName 文件参数名称，比如后端参数是@RequestPart("fileList") MultipartFile[] files，文件参数名就是fileList
     * @param fileParams    文件实体key是文件真实的名称，value是文件二进制内容
     * @param extraParam    除了文件之外的其他参数，文本格式的key-value参数
     * @param headers       请求头，注意里面不能设置content-type（因为是固定的 multipart/form-data）
     * @return
     */
    public static byte[] postFile(String url, String fileParamName, Map<String, byte[]> fileParams, Map<String, String> extraParam, Map<String, String> headers) {
        HttpUrl httpUrl = HttpUrl.parse(url);
        Request.Builder requestBuilder = new Request.Builder();
        requestBuilder.url(httpUrl);
        if (headers == null) {
            headers = EMPTY_PARAM;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            requestBuilder.addHeader(entry.getKey(), entry.getValue());
        }

        MultipartBody.Builder requestBodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        for (Map.Entry<String, String> entry : extraParam.entrySet()) {
            requestBodyBuilder.addFormDataPart(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<String, byte[]> entry : fileParams.entrySet()) {
            requestBodyBuilder.addFormDataPart(fileParamName, entry.getKey(), RequestBody.create(entry.getValue(), BINARY_MEDIA_TYPE));
        }

        Request request = requestBuilder.post(requestBodyBuilder.build()).build();

        Call call = client.newCall(request);
        try {
            Response response = call.execute();
            if (response.isSuccessful()) {
                logger.info("http post file success, url:{}, param:{}, headers:{}", url, extraParam, headers);
                return response.body().bytes();
            } else {
                logger.info("http post file failed, url:{}, param:{}, headers:{}, statusCode:{}", url, extraParam, headers, response.code());
                throw new IllegalStateException("http post failed, statusCode: " + response.code() + ", message: " + response.message());
            }
        } catch (IOException e) {
            logger.error("http post file error, url:{}, param:{}, headers:{}", url, extraParam, headers, e);
            call.cancel();
            throw new IllegalStateException("http post error: " + e.getMessage());
        }
    }
}
```
