package com.bwssystems.HABridge.hue;

import com.bwssystems.HABridge.BridgeSettings;
import com.bwssystems.HABridge.api.hue.DeviceResponse;
import com.bwssystems.HABridge.api.hue.DeviceState;
import com.bwssystems.HABridge.api.hue.HueConstants;
import com.bwssystems.HABridge.dao.DeviceDescriptor;
import com.bwssystems.HABridge.dao.DeviceRepository;
import com.google.gson.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static spark.Spark.*;
import spark.Request;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Philips Hue CLIP API v2 emulation.
 *
 * Registers routes under /clip/v2/resource and the SSE endpoint
 * /eventstream/clip/v2 alongside the existing v1 /api routes.
 *
 * Authentication uses the hue-application-key header (same username as v1).
 * All bridge→v2 UUID mappings are deterministic:
 *   light   UUID = 00000000-0000-0000-0000-<12-digit light id>
 *   device  UUID = 00000000-0000-0001-0000-<12-digit light id>
 *   bridge  UUID = derived from MAC
 */
public class ClipV2Resource {
    private static final Logger log = LoggerFactory.getLogger(ClipV2Resource.class);
    private static final String CLIP = "/clip/v2";

    private final HueMulator hueMulator;
    private final DeviceRepository repository;
    private final BridgeSettings bridgeSettingMaster;
    private final Gson gson = new GsonBuilder().create();

    // SSE: thread-safe list of connected response writers
    private static final List<PrintWriter> sseClients = new CopyOnWriteArrayList<>();

    public ClipV2Resource(HueMulator hueMulator, DeviceRepository deviceRepository,
            BridgeSettings bridgeMaster) {
        this.hueMulator = hueMulator;
        this.repository = deviceRepository;
        this.bridgeSettingMaster = bridgeMaster;
    }

    public void setupServer() {
        log.info("Hue CLIP API v2 service starting....");

        options(CLIP + "/*", (req, res) -> {
            res.header("Access-Control-Allow-Origin", req.headers("Origin"));
            res.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE");
            res.header("Access-Control-Allow-Headers",
                    "Content-Type, hue-application-key, " + req.headers("Access-Control-Request-Headers"));
            res.type("text/html");
            res.status(200);
            return "";
        });

        get(CLIP + "/resource", (req, res) -> {
            res.header("Access-Control-Allow-Origin", req.headers("Origin"));
            res.type("application/json");
            res.status(200);
            return buildAllResourcesResponse(req.ip());
        });

        get(CLIP + "/resource/light", (req, res) -> {
            res.header("Access-Control-Allow-Origin", req.headers("Origin"));
            res.type("application/json");
            res.status(200);
            return buildLightsResponse(appKey(req), req.ip());
        });

        get(CLIP + "/resource/light/:id", (req, res) -> {
            res.header("Access-Control-Allow-Origin", req.headers("Origin"));
            res.type("application/json");
            res.status(200);
            return buildSingleLightResponse(req.params(":id"), appKey(req), req.ip());
        });

        put(CLIP + "/resource/light/:id", (req, res) -> {
            res.header("Access-Control-Allow-Origin", req.headers("Origin"));
            res.type("application/json");
            res.status(200);
            return changeLightStateV2(req.params(":id"), req.body(), appKey(req), req.ip());
        });

        get(CLIP + "/resource/bridge", (req, res) -> {
            res.header("Access-Control-Allow-Origin", req.headers("Origin"));
            res.type("application/json");
            res.status(200);
            return buildBridgeResponse();
        });

        get(CLIP + "/resource/bridge_home", (req, res) -> {
            res.header("Access-Control-Allow-Origin", req.headers("Origin"));
            res.type("application/json");
            res.status(200);
            return buildBridgeHomeResponse(req.ip());
        });

        get(CLIP + "/resource/device", (req, res) -> {
            res.header("Access-Control-Allow-Origin", req.headers("Origin"));
            res.type("application/json");
            res.status(200);
            return buildDevicesResponse(appKey(req), req.ip());
        });

        // SSE event stream - keep connection alive, push state-change events
        get("/eventstream/clip/v2", (req, res) -> {
            res.raw().setContentType("text/event-stream");
            res.raw().setCharacterEncoding("UTF-8");
            res.raw().setHeader("Cache-Control", "no-cache");
            res.raw().setHeader("Connection", "keep-alive");
            res.raw().setHeader("Access-Control-Allow-Origin", "*");

            PrintWriter writer = res.raw().getWriter();
            sseClients.add(writer);
            log.debug("CLIP v2 SSE client connected from {}", req.ip());

            try {
                // Initial "hi" event that Alexa expects on connect
                writer.write("id: " + System.currentTimeMillis() + ":0\n");
                writer.write("data: " + buildHiEvent() + "\n\n");
                writer.flush();

                while (!writer.checkError()) {
                    Thread.sleep(30_000);
                    writer.write(": keepalive\n\n");
                    writer.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                sseClients.remove(writer);
                log.debug("CLIP v2 SSE client disconnected from {}", req.ip());
            }
            return "";
        });

        log.info("Hue CLIP API v2 service started.");
    }

    // -------------------------------------------------------------------------
    // Route handlers
    // -------------------------------------------------------------------------

    private String buildAllResourcesResponse(String ip) {
        JsonArray data = new JsonArray();
        List<DeviceDescriptor> devices = repository.findAllByRequester(ip);
        for (DeviceDescriptor d : devices) {
            if (!d.isInactive()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("rid", lightIdToUuid(d.getId()));
                entry.addProperty("rtype", "light");
                data.add(entry);
            }
        }
        return wrap(data);
    }

    @SuppressWarnings("unchecked")
    private String buildLightsResponse(String appKey, String ip) {
        Object raw = hueMulator.getLightsMapForV2(appKey, ip);
        if (!(raw instanceof Map)) return wrap(new JsonArray());
        Map<String, DeviceResponse> map = (Map<String, DeviceResponse>) raw;
        JsonArray data = new JsonArray();
        for (Map.Entry<String, DeviceResponse> entry : map.entrySet()) {
            data.add(toV2Light(entry.getKey(), entry.getValue()));
        }
        return wrap(data);
    }

    @SuppressWarnings("unchecked")
    private String buildSingleLightResponse(String uuid, String appKey, String ip) {
        String lightId;
        try {
            lightId = uuidToLightId(uuid);
        } catch (NumberFormatException e) {
            return wrapError("Invalid light id: " + uuid);
        }
        Object raw = hueMulator.getLightsMapForV2(appKey, ip);
        if (!(raw instanceof Map)) return wrapError("Could not retrieve lights");
        Map<String, DeviceResponse> map = (Map<String, DeviceResponse>) raw;
        DeviceResponse dr = map.get(lightId);
        if (dr == null) return wrapError("Light not found: " + lightId);
        JsonArray data = new JsonArray();
        data.add(toV2Light(lightId, dr));
        return wrap(data);
    }

    private String changeLightStateV2(String uuid, String v2Body, String appKey, String ip) {
        String lightId;
        try {
            lightId = uuidToLightId(uuid);
        } catch (NumberFormatException e) {
            return wrapError("Invalid light id: " + uuid);
        }
        String v1Body;
        try {
            v1Body = v2BodyToV1(v2Body);
        } catch (Exception e) {
            log.warn("Could not parse CLIP v2 body: {}", v2Body);
            return wrapError("Invalid request body");
        }

        String v1Result = hueMulator.changeStateForV2(lightId, v1Body, appKey, ip);
        if (v1Result != null && v1Result.contains("\"error\"")) {
            log.warn("CLIP v2 state change failed for light {}: {}", lightId, v1Result);
            return wrapError("State change failed");
        }

        // Push SSE event to all connected clients
        pushStateEvent(uuid, lightId, v1Body);

        return wrap(new JsonArray()); // 200 OK with empty data array = success
    }

    private String buildBridgeResponse() {
        String mac = bridgeSettingMaster.getBridgeSettingsDescriptor().getHubmac();
        String bridgeUuid = macToBridgeUuid(mac);
        JsonObject bridge = new JsonObject();
        bridge.addProperty("id", bridgeUuid);
        bridge.addProperty("id_v1", "/config");
        JsonObject owner = new JsonObject();
        owner.addProperty("rid", bridgeUuid);
        owner.addProperty("rtype", "bridge");
        bridge.add("owner", owner);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", "HA-Bridge");
        bridge.add("bridge_id", new JsonPrimitive(
                bridgeSettingMaster.getBridgeSettingsDescriptor().getHubmac()
                        .replace(":", "").toUpperCase()));
        bridge.addProperty("type", "bridge");
        JsonArray data = new JsonArray();
        data.add(bridge);
        return wrap(data);
    }

    private String buildBridgeHomeResponse(String ip) {
        List<DeviceDescriptor> devices = repository.findAllByRequester(ip);
        JsonArray childrenArr = new JsonArray();
        for (DeviceDescriptor d : devices) {
            if (!d.isInactive()) {
                JsonObject child = new JsonObject();
                child.addProperty("rid", lightIdToUuid(d.getId()));
                child.addProperty("rtype", "light");
                childrenArr.add(child);
            }
        }
        JsonObject home = new JsonObject();
        home.addProperty("id", "00000000-0000-0002-0000-000000000001");
        home.addProperty("id_v1", "/");
        home.add("children", childrenArr);
        home.addProperty("type", "bridge_home");
        JsonArray data = new JsonArray();
        data.add(home);
        return wrap(data);
    }

    @SuppressWarnings("unchecked")
    private String buildDevicesResponse(String appKey, String ip) {
        Object raw = hueMulator.getLightsMapForV2(appKey, ip);
        if (!(raw instanceof Map)) return wrap(new JsonArray());
        Map<String, DeviceResponse> map = (Map<String, DeviceResponse>) raw;
        JsonArray data = new JsonArray();
        for (Map.Entry<String, DeviceResponse> entry : map.entrySet()) {
            data.add(toV2Device(entry.getKey(), entry.getValue()));
        }
        return wrap(data);
    }

    // -------------------------------------------------------------------------
    // SSE helpers
    // -------------------------------------------------------------------------

    private static String buildHiEvent() {
        JsonArray events = new JsonArray();
        JsonObject hi = new JsonObject();
        hi.addProperty("creationtime", Instant.now().toString());
        hi.add("data", new JsonArray());
        hi.addProperty("id", UUID.randomUUID().toString());
        hi.addProperty("type", "update");
        events.add(hi);
        return new Gson().toJson(events);
    }

    private static void pushStateEvent(String uuid, String lightId, String v1Body) {
        if (sseClients.isEmpty()) return;
        try {
            JsonObject v1 = new Gson().fromJson(v1Body, JsonObject.class);
            JsonObject lightUpdate = new JsonObject();
            lightUpdate.addProperty("id", uuid);
            lightUpdate.addProperty("id_v1", "/lights/" + lightId);
            if (v1.has("on")) {
                JsonObject on = new JsonObject();
                on.addProperty("on", v1.get("on").getAsBoolean());
                lightUpdate.add("on", on);
            }
            if (v1.has("bri")) {
                double brightness = Math.round(v1.get("bri").getAsInt() / 254.0 * 10000.0) / 100.0;
                JsonObject dimming = new JsonObject();
                dimming.addProperty("brightness", brightness);
                lightUpdate.add("dimming", dimming);
            }
            lightUpdate.addProperty("type", "light");

            JsonArray eventData = new JsonArray();
            eventData.add(lightUpdate);
            JsonObject event = new JsonObject();
            event.addProperty("creationtime", Instant.now().toString());
            event.add("data", eventData);
            event.addProperty("id", UUID.randomUUID().toString());
            event.addProperty("type", "update");
            JsonArray events = new JsonArray();
            events.add(event);

            String msg = "id: " + System.currentTimeMillis() + ":0\ndata: "
                    + new Gson().toJson(events) + "\n\n";

            Iterator<PrintWriter> it = sseClients.iterator();
            while (it.hasNext()) {
                PrintWriter writer = it.next();
                writer.write(msg);
                writer.flush();
                if (writer.checkError()) sseClients.remove(writer);
            }
        } catch (Exception e) {
            log.warn("Could not push SSE state event: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // V1 → V2 conversion
    // -------------------------------------------------------------------------

    private JsonObject toV2Light(String lightId, DeviceResponse dr) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", lightIdToUuid(lightId));
        obj.addProperty("id_v1", "/lights/" + lightId);

        JsonObject owner = new JsonObject();
        owner.addProperty("rid", deviceIdToUuid(lightId));
        owner.addProperty("rtype", "device");
        obj.add("owner", owner);

        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", dr.getName() != null ? dr.getName() : "Light " + lightId);
        metadata.addProperty("archetype", "sultan_bulb");
        obj.add("metadata", metadata);

        DeviceState state = dr.getState();
        if (state != null) {
            JsonObject on = new JsonObject();
            on.addProperty("on", state.isOn());
            obj.add("on", on);

            JsonObject dimming = new JsonObject();
            dimming.addProperty("brightness", Math.round(state.getBri() / 254.0 * 10000.0) / 100.0);
            dimming.addProperty("min_dim_level", 0.10);
            obj.add("dimming", dimming);

            boolean isColor = "Extended color light".equals(dr.getType());
            if (isColor) {
                if (state.getCt() > 0) {
                    JsonObject ct = new JsonObject();
                    ct.addProperty("mirek", state.getCt());
                    ct.addProperty("mirek_valid", state.getCt() >= 153 && state.getCt() <= 500);
                    JsonObject schema = new JsonObject();
                    schema.addProperty("mirek_minimum", 153);
                    schema.addProperty("mirek_maximum", 500);
                    ct.add("mirek_schema", schema);
                    obj.add("color_temperature", ct);
                }
                if (state.getXy() != null && state.getXy().size() >= 2) {
                    JsonObject color = new JsonObject();
                    JsonObject xy = new JsonObject();
                    xy.addProperty("x", state.getXy().get(0));
                    xy.addProperty("y", state.getXy().get(1));
                    color.add("xy", xy);
                    color.addProperty("gamut_type", "C");
                    JsonObject gamut = new JsonObject();
                    addXY(gamut, "red",   0.6915, 0.3083);
                    addXY(gamut, "green", 0.17,   0.7);
                    addXY(gamut, "blue",  0.1532, 0.0475);
                    color.add("gamut", gamut);
                    obj.add("color", color);
                }
            }
        }

        obj.addProperty("mode", "normal");
        obj.addProperty("type", "light");
        return obj;
    }

    private JsonObject toV2Device(String lightId, DeviceResponse dr) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", deviceIdToUuid(lightId));
        obj.addProperty("id_v1", "/lights/" + lightId);

        JsonObject metadata = new JsonObject();
        metadata.addProperty("name", dr.getName() != null ? dr.getName() : "Device " + lightId);
        metadata.addProperty("archetype", "sultan_bulb");
        obj.add("metadata", metadata);

        JsonArray services = new JsonArray();
        JsonObject svc = new JsonObject();
        svc.addProperty("rid", lightIdToUuid(lightId));
        svc.addProperty("rtype", "light");
        services.add(svc);
        obj.add("services", services);

        JsonObject productData = new JsonObject();
        productData.addProperty("manufacturer_name", dr.getManufacturername() != null ? dr.getManufacturername() : "Philips");
        productData.addProperty("model_id", dr.getModelid() != null ? dr.getModelid() : HueConstants.MODEL_ID);
        productData.addProperty("software_version", HueConstants.API_VERSION);
        productData.addProperty("certified", true);
        obj.add("product_data", productData);

        obj.addProperty("type", "device");
        return obj;
    }

    // -------------------------------------------------------------------------
    // V2 PUT body → V1 StateChangeBody JSON
    // -------------------------------------------------------------------------

    private String v2BodyToV1(String v2Body) {
        JsonObject v2 = gson.fromJson(v2Body, JsonObject.class);
        JsonObject v1 = new JsonObject();

        if (v2.has("on") && v2.get("on").isJsonObject()) {
            JsonObject onObj = v2.getAsJsonObject("on");
            if (onObj.has("on")) v1.addProperty("on", onObj.get("on").getAsBoolean());
        }

        if (v2.has("dimming") && v2.get("dimming").isJsonObject()) {
            JsonObject dimming = v2.getAsJsonObject("dimming");
            if (dimming.has("brightness")) {
                double pct = dimming.get("brightness").getAsDouble();
                int bri = (int) Math.round(pct / 100.0 * 254.0);
                v1.addProperty("bri", Math.max(1, Math.min(254, bri)));
            }
        }

        if (v2.has("color_temperature") && v2.get("color_temperature").isJsonObject()) {
            JsonObject ct = v2.getAsJsonObject("color_temperature");
            if (ct.has("mirek")) v1.addProperty("ct", ct.get("mirek").getAsInt());
        }

        if (v2.has("color") && v2.get("color").isJsonObject()) {
            JsonObject color = v2.getAsJsonObject("color");
            if (color.has("xy") && color.get("xy").isJsonObject()) {
                JsonObject xyObj = color.getAsJsonObject("xy");
                JsonArray xyArr = new JsonArray();
                xyArr.add(xyObj.get("x").getAsDouble());
                xyArr.add(xyObj.get("y").getAsDouble());
                v1.add("xy", xyArr);
            }
        }

        return gson.toJson(v1);
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private String appKey(Request req) {
        String key = req.headers("hue-application-key");
        if (key != null && !key.isEmpty()) return key;
        Map<String, ?> wl = bridgeSettingMaster.getBridgeSecurity().getWhitelist();
        if (wl != null && !wl.isEmpty()) return wl.keySet().iterator().next();
        return "ha-bridge-v2-internal";
    }

    private static String lightIdToUuid(String id) {
        return String.format("00000000-0000-0000-0000-%012d", Long.parseLong(id));
    }

    private static String deviceIdToUuid(String id) {
        return String.format("00000000-0000-0001-0000-%012d", Long.parseLong(id));
    }

    private static String uuidToLightId(String uuid) {
        String last = uuid.substring(uuid.lastIndexOf('-') + 1);
        return String.valueOf(Long.parseLong(last));
    }

    private static String macToBridgeUuid(String mac) {
        if (mac == null) return "00000000-0000-0003-0000-000000000001";
        String clean = mac.replace(":", "").toLowerCase();
        return "00000000-0000-0003-" + clean.substring(0, 4) + "-" + clean.substring(4);
    }

    private static void addXY(JsonObject parent, String name, double x, double y) {
        JsonObject xy = new JsonObject();
        xy.addProperty("x", x);
        xy.addProperty("y", y);
        parent.add(name, xy);
    }

    private String wrap(JsonArray data) {
        JsonObject env = new JsonObject();
        env.add("data", data);
        env.add("errors", new JsonArray());
        return gson.toJson(env);
    }

    private String wrapError(String description) {
        JsonObject env = new JsonObject();
        env.add("data", new JsonArray());
        JsonArray errors = new JsonArray();
        JsonObject err = new JsonObject();
        err.addProperty("description", description);
        errors.add(err);
        env.add("errors", errors);
        return gson.toJson(env);
    }
}
