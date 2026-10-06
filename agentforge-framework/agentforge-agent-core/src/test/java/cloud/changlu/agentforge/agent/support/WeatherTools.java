package cloud.changlu.agentforge.agent.support;

import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

/**
 * @description 测试用天气工具组
 * @author changlu
 * @date 2026/9/16
 */
public class WeatherTools {

    @Tool(name = "getWeather", value = "Returns the weather for the given city")
    public String getWeather(@P("The city name") String city) {
        return "The weather in " + city + " is 22 degrees Celsius and sunny.";
    }

    @Tool(name = "getWeatherAdvice", value = "Returns clothing advice for the given city")
    public String getWeatherAdvice(@P("The city name") String city) {
        return "mild weather in " + city + ", light jacket recommended.";
    }
}
