package cloud.changlu.agentforge.agent.tool.http.support;

import cloud.changlu.agentforge.model.http.HttpRequest;
import cloud.changlu.agentforge.model.http.HttpResponse;
import cloud.changlu.agentforge.model.http.HttpTransport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * @description 测试用HTTP传输实现：记录每次请求，并按顺序返回预置的成功响应或异常
 * @author changlu
 * @date 2026/10/04
 */
public class RecordingHttpTransport implements HttpTransport {

    public final List<HttpRequest> requests = new ArrayList<HttpRequest>();

    private final List<Object> outcomes = new ArrayList<Object>();

    public RecordingHttpTransport enqueue(HttpResponse response) {
        outcomes.add(response);
        return this;
    }

    public RecordingHttpTransport enqueue(IOException error) {
        outcomes.add(error);
        return this;
    }

    public HttpRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public HttpResponse execute(HttpRequest request) throws IOException {
        requests.add(request);
        int index = requests.size() - 1;
        Object outcome =
                index < outcomes.size() ? outcomes.get(index) : new HttpResponse(200, "{}");
        if (outcome instanceof IOException) {
            throw (IOException) outcome;
        }
        return (HttpResponse) outcome;
    }
}
