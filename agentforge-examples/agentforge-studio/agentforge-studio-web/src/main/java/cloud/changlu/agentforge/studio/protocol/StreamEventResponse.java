package cloud.changlu.agentforge.studio.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Studio unified SSE event. eventId and deliveryId are intentionally omitted for the first phase.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamEventResponse {

    private String event;
    private String type;
    private String title;
    private Object content;
    private Boolean isFinish;

    public StreamEventResponse() {}

    private StreamEventResponse(String event) {
        this.event = event;
    }

    public static StreamEventResponse of(String event) {
        return new StreamEventResponse(event);
    }

    public StreamEventResponse type(String type) {
        this.type = type;
        return this;
    }

    public StreamEventResponse title(String title) {
        this.title = title;
        return this;
    }

    public StreamEventResponse content(Object content) {
        this.content = content;
        return this;
    }

    public StreamEventResponse finish(Boolean finish) {
        isFinish = finish;
        return this;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }

    public Boolean getIsFinish() {
        return isFinish;
    }

    public void setIsFinish(Boolean finish) {
        isFinish = finish;
    }
}
