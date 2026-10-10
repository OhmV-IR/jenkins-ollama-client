package io.ohmvir.plugins.ollamaclient;

import io.ohmvir.plugins.jenkinsaisynapse.api.tools.Tool;
import io.ohmvir.plugins.jenkinsaisynapse.api.tools.ToolArgumentDescription;
import java.util.List;

final class TestTools {
    private TestTools() {}

    /** A tool with a required and an optional argument, used to check the generated schema. */
    public static class ForecastTool extends Tool {
        public ForecastTool() throws NoSuchMethodException {
            super();
        }

        @Override
        public String getName() {
            return "getForecast";
        }

        @Override
        public String getDescription() {
            return "Gets the forecast for a city";
        }

        @Override
        public List<ToolArgumentDescription> getArguments() {
            return List.of(
                    new ToolArgumentDescription("city", String.class, "Name of the city", true),
                    new ToolArgumentDescription("days", int.class, "Number of days", false));
        }

        public String getForecast(String city, int days) {
            return days + " days of sun in " + city;
        }
    }

    /** A tool the model can call end to end through ModelRequest#execute. */
    public static class WeatherTool extends Tool {
        public WeatherTool() throws NoSuchMethodException {
            super();
        }

        @Override
        public String getName() {
            return "getWeather";
        }

        @Override
        public String getDescription() {
            return "Gets the current weather for a city";
        }

        @Override
        public List<ToolArgumentDescription> getArguments() {
            return List.of(new ToolArgumentDescription("city", String.class, "Name of the city", true));
        }

        public String getWeather(String city) {
            return "Sunny in " + city;
        }
    }
}
