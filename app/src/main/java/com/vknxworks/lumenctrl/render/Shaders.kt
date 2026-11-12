package com.vknxworks.lumenctrl.render

import com.vknxworks.lumenctrl.model.MAX_MASKS
import com.vknxworks.lumenctrl.model.MAX_SPOTS
import com.vknxworks.lumenctrl.model.Param

/**
 * Lumen's develop pipeline in one fragment-shader pass; preview, thumbnails and
 * export all run it. Order: geometry + lens warp + chromatic aberration ->
 * heal spots -> local masks -> lens blur -> defringe -> noise reduction ->
 * sharpen -> texture -> (linear) white balance, exposure, lens vignetting,
 * dehaze, glow -> tone -> clarity -> contrast -> curves LUT -> fade ->
 * vibrance/saturation -> color mixer -> color grading -> vignette -> grain.
 *
 * Global params are packed into vec4 u_p[]; the #defines below are generated
 * from the Param enum so names stay in sync.
 */
object Shaders {
    val VERTEX = """
        #version 300 es
        layout(location = 0) in vec2 aPos;
        uniform float u_flipY;
        out vec2 vUv;
        void main() {
            vUv = aPos * 0.5 + 0.5;
            if (u_flipY > 0.5) vUv.y = 1.0 - vUv.y;
            gl_Position = vec4(aPos, 0.0, 1.0);
        }
    """.trimIndent()

    private val defines: String = buildString {
        Param.entries.forEachIndexed { i, p ->
            append("#define P_${p.key} u_p[${i / 4}].${"xyzw"[i % 4]}\n")
        }
        append("#define IDX_HUE ${Param.HUE_RED.ordinal}\n")
        append("#define IDX_SAT ${Param.SAT_RED.ordinal}\n")
        append("#define IDX_LUM ${Param.LUM_RED.ordinal}\n")
        append("#define NP ${Param.PACKED_VEC4}\n")
        append("#define MAXM $MAX_MASKS\n")
        append("#define MAXS $MAX_SPOTS\n")
    }

    val FRAGMENT: String = "#version 300 es\nprecision highp float;\nprecision highp int;\n" + defines + """
in vec2 vUv;
out vec4 fragColor;

uniform sampler2D u_tex;
uniform sampler2D u_lut;
uniform sampler2D u_ai;
uniform sampler2D u_gain;     // source Ultra HDR gain map (R), if any
uniform int u_gainOn;
uniform vec3 u_gainParams;    // logMin, logMax, gamma
uniform vec2 u_gainEps;       // epsSdr, epsHdr
uniform sampler2D u_dn;       // AI-denoised base, if computed
uniform int u_dnOn;
uniform float u_dnLod;        // log2(source size / denoised size)
uniform int u_outMode;        // 0 = SDR image, 1 = HDR gain map
uniform int u_vizHdr;         // tint pixels brighter than SDR white
uniform vec2 u_texSize;
uniform vec2 u_outSize;
uniform mat3 u_H;
uniform float u_before;
uniform vec3 u_ground;
uniform vec4 u_p[NP];
uniform int u_maskCount;
uniform vec4 u_mask[MAXM * 5];
uniform int u_showMask;
uniform int u_spotCount;
uniform vec4 u_spot[MAXS * 2];
uniform int u_curveOn;
uniform int u_mixOn;

float P(int i) { return u_p[i / 4][i % 4]; }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

vec3 hue2rgb(float h) {
    h = fract(h / 360.0) * 6.0;
    return clamp(vec3(abs(h - 3.0) - 1.0, 2.0 - abs(h - 2.0), 2.0 - abs(h - 4.0)), 0.0, 1.0);
}
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1.0e-10)), d / (q.x + 1.0e-10), q.x);
}
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}
float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float srcAspect() { return u_texSize.x / u_texSize.y; }

vec2 toSrc(vec2 uv) { vec3 p = u_H * vec3(uv, 1.0); return p.xy / p.z; }

// Radial lens distortion around the image centre (k < 0 = barrel correction).
vec2 lensWarp(vec2 s, float k) {
    float a = srcAspect();
    vec2 d = (s - 0.5) * vec2(a, 1.0);
    float r2 = dot(d, d) / (0.25 * (a * a + 1.0));
    d *= 1.0 + k * r2;
    return d / vec2(a, 1.0) + 0.5;
}
bool outside(vec2 s) { return any(lessThan(s, vec2(0.0))) || any(greaterThan(s, vec2(1.0))); }

// Average of 8 samples on a ring, weighted toward direction dn: the
// low-frequency color a heal spot should blend into.
vec3 ringAvg(vec2 centre, float r, vec2 dn, float lod) {
    float a = srcAspect();
    vec3 acc = vec3(0.0);
    float ws = 0.0;
    for (int k = 0; k < 8; k++) {
        float ang = float(k) * 0.785398;
        vec2 dir = vec2(cos(ang), sin(ang));
        float w = pow(max(dot(dn, dir), 0.0), 2.0) + 0.08;
        acc += textureLod(u_tex, centre + dir * r * 1.15 / vec2(a, 1.0), lod).rgb * w;
        ws += w;
    }
    return acc / ws;
}

vec3 applySpots(vec3 c, vec2 s, float lod0) {
    float a = srcAspect();
    for (int i = 0; i < MAXS; i++) {
        if (i >= u_spotCount) break;
        vec4 A = u_spot[2 * i];
        vec4 B = u_spot[2 * i + 1];
        vec2 d = (s - A.xy) * vec2(a, 1.0);
        float dist = length(d);
        if (dist >= B.x) continue;
        float w = (1.0 - smoothstep(B.x * (1.0 - B.y), B.x, dist)) * B.z;
        vec2 ss = s + (A.zw - A.xy);
        vec3 S = textureLod(u_tex, ss, lod0).rgb;
        if (B.w < 0.5) {
            // Heal: take texture from the source, but low-frequency color from around the target.
            vec2 dn = dist > 1.0e-5 ? d / dist : vec2(1.0, 0.0);
            float lr = log2(max(B.x * u_texSize.y * 0.35, 1.0));
            S += ringAvg(A.xy, B.x, dn, lr) - ringAvg(A.zw, B.x, dn, lr);
        }
        c = mix(c, S, w);
    }
    return c;
}

float maskWeight(int i, vec2 s, vec3 sc) {
    float a = srcAspect();
    vec4 g = u_mask[5 * i];
    vec4 m = u_mask[5 * i + 1];
    int type = int(m.x + 0.5);
    float w;
    if (type == 0) {
        vec2 A = g.xy * vec2(a, 1.0);
        vec2 AB = g.zw * vec2(a, 1.0) - A;
        float t = dot(s * vec2(a, 1.0) - A, AB) / max(dot(AB, AB), 1.0e-6);
        w = 1.0 - smoothstep(0.0, 1.0, t);
    } else if (type == 1) {
        vec2 d = (s - g.xy) * vec2(a, 1.0) / max(g.zw, vec2(1.0e-4));
        w = 1.0 - smoothstep(1.0 - max(m.y, 0.01), 1.0, length(d));
    } else if (type == 2) {
        float L = luma(sc);
        float f = max(m.y * 0.25, 0.005);
        w = smoothstep(g.x - f, g.x + f, L) * (1.0 - smoothstep(g.y - f, g.y + f, L));
    } else if (type == 3) {
        vec3 h = rgb2hsv(sc);
        float dh = abs(fract(h.x - g.x + 0.5) - 0.5);
        float wd = max(g.y, 0.01);
        w = (1.0 - smoothstep(wd * (1.0 - m.y * 0.9), wd, dh)) * smoothstep(0.06, 0.2, h.y);
    } else {
        // AI mask: channel g.x of the mask atlas; feather softens or hardens the edge.
        float v = textureLod(u_ai, s, 0.0)[int(g.x + 0.5)];
        float f = 0.02 + m.y * 0.48;
        w = smoothstep(0.5 - f, 0.5 + f, v);
    }
    if (m.z > 0.5) w = 1.0 - w;
    return clamp(w, 0.0, 1.0);
}

vec3 blur5(vec2 s, float lod) {
    vec2 o = exp2(lod) / u_texSize;
    return (textureLod(u_tex, s, lod).rgb * 2.0
        + textureLod(u_tex, s + vec2(o.x, 0.0), lod).rgb
        + textureLod(u_tex, s - vec2(o.x, 0.0), lod).rgb
        + textureLod(u_tex, s + vec2(0.0, o.y), lod).rgb
        + textureLod(u_tex, s - vec2(0.0, o.y), lod).rgb) / 6.0;
}

void main() {
    vec2 uv = vec2(vUv.x, 1.0 - vUv.y);
    vec2 s0 = toSrc(uv);
    float kDist = P_distortion * 0.35;
    vec2 s = lensWarp(s0, kDist);
    // Derivatives first, while control flow is still uniform.
    vec2 ddx = dFdx(s) * u_texSize;
    vec2 ddy = dFdy(s) * u_texSize;
    float baseLod = max(0.0, log2(max(max(length(ddx), length(ddy)), 1.0e-6)));
    float texMax = max(u_texSize.x, u_texSize.y);
    float a = srcAspect();
    if (outside(s)) { fragColor = vec4(u_ground, 1.0); return; }

    vec3 c;
    if (abs(P_chromatic) > 0.0005) {
        c.r = textureLod(u_tex, lensWarp(s0, kDist + P_chromatic * 0.006), baseLod).r;
        c.g = textureLod(u_tex, s, baseLod).g;
        c.b = textureLod(u_tex, lensWarp(s0, kDist - P_chromatic * 0.006), baseLod).b;
    } else {
        c = textureLod(u_tex, s, baseLod).rgb;
    }
    if (u_before > 0.5) { fragColor = vec4(c, 1.0); return; }

    // AI Denoise: blend toward the denoised base (fine detail only; coarse mips are already clean).
    if (u_dnOn == 1 && P_aiDenoise > 0.001) {
        vec3 dn = textureLod(u_dn, s, max(0.0, baseLod - u_dnLod)).rgb;
        c = mix(c, dn, P_aiDenoise);
    }

    c = applySpots(c, s, baseLod);
    vec3 srcC = c;

    // Ultra HDR source: how much brighter than its SDR base the original pixel was.
    float srcBoost = 1.0;
    if (u_gainOn == 1) {
        float gm = textureLod(u_gain, s, 0.0).r;
        float lb = mix(u_gainParams.x, u_gainParams.y, pow(gm, 1.0 / u_gainParams.z));
        float sl = luma(pow(max(c, 0.0), vec3(2.2)));
        srcBoost = max(((sl + u_gainEps.x) * exp2(lb) - u_gainEps.y) / max(sl, 1.0e-4), 1.0);
    }

    // Local masks add to the global sliders per pixel.
    vec4 la = vec4(0.0), lb = vec4(0.0), lc = vec4(0.0);
    float showW = 0.0;
    for (int i = 0; i < MAXM; i++) {
        if (i >= u_maskCount) break;
        float w = maskWeight(i, s, srcC);
        la += w * u_mask[5 * i + 2];
        lb += w * u_mask[5 * i + 3];
        lc += w * u_mask[5 * i + 4];
        if (i == u_showMask) showW = w;
    }
    float exposure = P_exposure + la.x;
    float contrast = P_contrast + la.y;
    float highlights = P_highlights + la.z;
    float shadows = P_shadows + la.w;
    float temperature = P_temperature + lb.x;
    float tint = P_tint + lb.y;
    float saturation = P_saturation + lb.z;
    float clarity = P_clarity + lb.w;
    float texAmt = P_texture + lc.x;
    float dehaze = P_dehaze + lc.y;

    // Lens blur: synthetic depth from a focus point (radial) or band (tilt-shift).
    float blurW = 0.0;
    if (P_blurAmount > 0.001) {
        vec2 f = vec2(P_blurFocusX, P_blurFocusY);
        float d = P_blurMode > 0.5 ? abs(s.y - f.y) : length((s - f) * vec2(a, 1.0));
        float size = P_blurFocusSize * 0.6;
        blurW = smoothstep(size, size + 0.02 + P_blurFeather * 0.5, d);
        if (blurW > 0.001) {
            float radPx = blurW * P_blurAmount * texMax * 0.02;
            float lodB = max(baseLod, log2(max(radPx * 0.5, 1.0)));
            vec2 o = radPx / u_texSize;
            vec3 acc = textureLod(u_tex, s, lodB).rgb;
            for (int k = 0; k < 6; k++) {
                float ang = float(k) * 1.0472 + 0.3;
                acc += textureLod(u_tex, s + vec2(cos(ang), sin(ang)) * o, lodB).rgb;
            }
            c = mix(c, acc / 7.0, smoothstep(0.0, 0.15, blurW * P_blurAmount));
        }
    }
    float detailKeep = 1.0 - blurW;

    // Defringe: desaturate purple/green fringes at high-contrast edges.
    if (P_defringePurple + P_defringeGreen > 0.001) {
        vec3 nb = textureLod(u_tex, s, baseLod + 1.5).rgb;
        float edge = smoothstep(0.02, 0.1, abs(luma(srcC) - luma(nb)));
        vec3 h = rgb2hsv(clamp(c, 0.0, 1.0));
        float purple = 1.0 - smoothstep(0.05, 0.1, abs(h.x - 0.78));
        float green = 1.0 - smoothstep(0.05, 0.1, abs(h.x - 0.33));
        float k = edge * (purple * P_defringePurple + green * P_defringeGreen) * 1.5;
        c = mix(c, vec3(luma(c)), clamp(k, 0.0, 1.0));
    }

    // Noise reduction: luminance (edge-aware) and color (chroma smoothing).
    if (P_noiseLum > 0.001) {
        vec3 nb = textureLod(u_tex, s, baseLod + 0.6 + 1.6 * P_noiseLum).rgb;
        float diff = luma(nb) - luma(c);
        float thr = 0.02 + 0.2 * (1.0 - P_noiseDetail);
        float keep = smoothstep(thr * 0.5, thr, abs(diff));
        c += diff * P_noiseLum * (1.0 - keep);
    }
    if (P_noiseColor > 0.001) {
        vec3 cb = textureLod(u_tex, s, baseLod + 1.5 + 2.5 * P_noiseColor).rgb;
        c = mix(c, cb - luma(cb) + luma(c), min(P_noiseColor * 1.5, 1.0));
    }

    // Sharpening: unsharp mask with radius, detail and edge masking.
    if (P_sharpen > 0.001) {
        vec3 soft = textureLod(u_tex, s, baseLod + 0.5 + 1.8 * P_sharpenRadius).rgb;
        vec3 hp = c - soft;
        float m = P_sharpenMasking < 0.01 ? 1.0 :
            smoothstep(P_sharpenMasking * 0.05, P_sharpenMasking * 0.05 + 0.03, abs(luma(hp)));
        c += hp * P_sharpen * (1.0 + 2.5 * P_sharpenDetail) * m * detailKeep;
    }
    // Texture: mid-frequency band.
    if (abs(texAmt) > 0.001) {
        vec3 b1 = textureLod(u_tex, s, baseLod + 1.5).rgb;
        vec3 b2 = textureLod(u_tex, s, baseLod + 3.5).rgb;
        c += (luma(b1) - luma(b2)) * texAmt * 1.2 * detailKeep;
    }
    // Clarity base: a blur about 1.5% of the image.
    float localDetail = 0.0;
    if (abs(clarity) > 0.001) {
        localDetail = (luma(c) - luma(blur5(s, log2(texMax * 0.015)))) * detailKeep;
    }

    // Linear light: white balance, exposure, lens vignetting, dehaze, glow.
    vec3 lin = pow(max(c, 0.0), vec3(2.2));
    lin *= vec3(1.0 + 0.3 * temperature, 1.0, 1.0 - 0.3 * temperature);
    lin *= vec3(1.0 + 0.15 * tint, 1.0 - 0.2 * tint, 1.0 + 0.15 * tint);
    lin *= exp2(exposure);
    if (abs(P_lensVignette) > 0.001) {
        vec2 d = (s - 0.5) * vec2(a, 1.0);
        lin *= 1.0 + P_lensVignette * 0.8 * dot(d, d) / (0.25 * (a * a + 1.0));
    }
    if (dehaze > 0.0) {
        float k = 0.12 * min(dehaze, 1.5);
        lin = max(lin - k, 0.0) / (1.0 - k);
    } else {
        lin = mix(lin, vec3(0.55), -dehaze * 0.35);
    }
    if (P_glow > 0.001) {
        vec3 bl = blur5(s, log2(texMax * (0.008 + 0.04 * P_glowSize)));
        vec3 bright = max(pow(bl, vec3(2.2)) * exp2(exposure) - 0.3, 0.0);
        lin += bright * vec3(1.0 + 0.3 * P_glowWarmth, 1.0, 1.0 - 0.3 * P_glowWarmth) * P_glow * 2.0;
    }
    vec3 linScene = lin;   // scene light before SDR clipping, for HDR
    float hdrOn = step(0.5, P_hdr);
    c = pow(max(lin, 0.0), vec3(1.0 / 2.2));

    // Tone: luminance-masked lifts and cuts. In HDR mode, SDR highlights shape only
    // the SDR base; the gain map then makes up the difference on HDR screens.
    float L = clamp(luma(c), 0.0, 1.0);
    c += (highlights + hdrOn * P_sdrHighlights) * 0.30 * smoothstep(0.45, 1.0, L)
       + shadows    * 0.30 * (1.0 - smoothstep(0.0, 0.55, L))
       + P_whites   * 0.20 * smoothstep(0.7, 1.0, L)
       + P_blacks   * 0.20 * (1.0 - smoothstep(0.0, 0.3, L));

    // Clarity: midtone-weighted local contrast.
    c += localDetail * clarity * 1.5 * (1.0 - pow(abs(2.0 * L - 1.0), 2.0));

    // Contrast.
    c = clamp(c, 0.0, 1.0);
    vec3 sCurve = c * c * (3.0 - 2.0 * c);
    c = contrast >= 0.0 ? mix(c, sCurve, min(contrast, 1.0)) : mix(c, vec3(0.5) + (c - 0.5) * 0.4, min(-contrast, 1.0));

    // Curves: master (with parametric) then per channel.
    if (u_curveOn == 1) {
        vec3 cc = clamp(c, 0.0, 1.0) * (255.0 / 256.0) + 0.5 / 256.0;
        c = vec3(textureLod(u_lut, vec2(cc.r, 0.5), 0.0).r,
                 textureLod(u_lut, vec2(cc.g, 0.5), 0.0).r,
                 textureLod(u_lut, vec2(cc.b, 0.5), 0.0).r);
        cc = c * (255.0 / 256.0) + 0.5 / 256.0;
        c = vec3(textureLod(u_lut, vec2(cc.r, 0.5), 0.0).g,
                 textureLod(u_lut, vec2(cc.g, 0.5), 0.0).b,
                 textureLod(u_lut, vec2(cc.b, 0.5), 0.0).a);
    }

    // Fade lifts the blacks and compresses the whites.
    c = mix(c, c * 0.82 + 0.12, P_fade);

    // Vibrance and saturation.
    float L2 = luma(c);
    float sat = max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b);
    c = mix(vec3(L2), c, 1.0 + P_vibrance * (1.0 - sat));
    c = mix(vec3(L2), c, 1.0 + saturation);

    // Color mixer: per-band hue/saturation/luminance, interpolated between band centres.
    if (u_mixOn == 1) {
        vec3 h = rgb2hsv(clamp(c, 0.0, 1.0));
        float deg = h.x * 360.0;
        float centres[9] = float[9](0.0, 30.0, 60.0, 120.0, 180.0, 225.0, 270.0, 315.0, 360.0);
        int i0 = 7;
        for (int k = 0; k < 8; k++) {
            if (deg >= centres[k] && deg < centres[k + 1]) { i0 = k; break; }
        }
        float t = (deg - centres[i0]) / (centres[i0 + 1] - centres[i0]);
        int i1 = (i0 + 1) % 8;
        float satW = smoothstep(0.02, 0.15, h.y);
        h.x = fract(h.x + mix(P(IDX_HUE + i0), P(IDX_HUE + i1), t) * satW * 30.0 / 360.0);
        h.y = clamp(h.y * (1.0 + mix(P(IDX_SAT + i0), P(IDX_SAT + i1), t) * satW), 0.0, 1.0);
        h.z = clamp(h.z * (1.0 + mix(P(IDX_LUM + i0), P(IDX_LUM + i1), t) * 0.5 * h.y * satW), 0.0, 1.0);
        c = hsv2rgb(h);
    }

    // Color grading: shadows / midtones / highlights / global.
    {
        float Lg = clamp(luma(c), 0.0, 1.0);
        float pivot = 0.5 + 0.3 * P_gradeBalance;
        float ex = 1.0 / (0.35 + P_gradeBlending * 1.3);
        float ws = pow(clamp(1.0 - Lg / pivot, 0.0, 1.0), ex);
        float wh = pow(clamp((Lg - pivot) / (1.0 - pivot), 0.0, 1.0), ex);
        float wm = 1.0 - max(ws, wh);
        c += ws * ((hue2rgb(P_shadowHue) - 0.5) * P_shadowSat * 0.35 + P_shadowLum * 0.25)
           + wm * ((hue2rgb(P_midHue) - 0.5) * P_midSat * 0.3 + P_midLum * 0.2)
           + wh * ((hue2rgb(P_highlightHue) - 0.5) * P_highlightSat * 0.35 + P_highlightLum * 0.25)
           + (hue2rgb(P_globalHue) - 0.5) * P_globalSat * 0.25 + P_globalLum * 0.15;
    }

    // Post-crop vignette with midpoint, roundness, feather and highlight priority.
    float aspect = u_outSize.x / u_outSize.y;
    if (abs(P_vignette) > 0.001) {
        vec2 vp = (uv - 0.5) * 2.0;
        float rEll = length(vp) / 1.41421;
        float rCir = length(vp * vec2(aspect, 1.0)) / length(vec2(aspect, 1.0));
        float rBox = max(abs(vp.x), abs(vp.y));
        float r = P_vigRoundness >= 0.0 ? mix(rEll, rCir, P_vigRoundness) : mix(rEll, rBox, -P_vigRoundness);
        float mid = 0.15 + 0.7 * P_vigMidpoint;
        float v = smoothstep(mid, mid + 0.05 + P_vigFeather * 0.9, r);
        c = P_vignette < 0.0
            ? c * (1.0 + P_vignette * v * (1.0 - P_vigHighlights * smoothstep(0.6, 1.0, luma(c))))
            : mix(c, vec3(1.0), P_vignette * v);
    }

    // Film grain on an image-relative grid, so preview matches export.
    if (P_grain > 0.001) {
        float cells = 1600.0 * (1.5 - P_grainSize * 1.2);
        vec2 gp = uv * vec2(cells * aspect, cells);
        float n1 = hash(floor(gp) + 0.37) - 0.5;
        float n2 = hash(floor(gp * 0.5) + 1.7) - 0.5;
        float n = mix(n2, n1, 0.35 + 0.65 * P_grainRoughness);
        float lum = clamp(luma(c), 0.0, 1.0);
        c += n * P_grain * 0.2 * (0.6 + 1.6 * lum * (1.0 - lum));
    }

    // HDR gain: target HDR luminance over the SDR result, limited to the HDR headroom.
    float gain = 1.0;
    if (hdrOn > 0.5) {
        float head = exp2(P_hdrLimit);
        float ls = luma(pow(clamp(c, 0.0, 1.0), vec3(2.2)));
        float le = luma(linScene) * srcBoost;
        float boost = 1.0 + P_hdrBoost * smoothstep(0.55, 1.0, luma(linScene)) * (head - 1.0);
        float target = max(le * boost, ls);
        gain = clamp(target / max(ls, 0.02), 1.0, head);
        gain = mix(1.0, gain, smoothstep(0.2, 0.6, max(ls, min(le, 1.0))));  // shadows stay SDR
    }
    if (u_outMode == 1) {
        fragColor = vec4(vec3(clamp(log2(gain) / max(P_hdrLimit, 0.01), 0.0, 1.0)), 1.0);
        return;
    }
    if (u_vizHdr == 1 && hdrOn > 0.5) {
        float t = clamp(log2(gain) / max(P_hdrLimit, 0.01), 0.0, 1.0);
        vec3 heat = mix(vec3(1.0, 0.9, 0.25), vec3(1.0, 0.25, 0.1), t);
        // Only tint meaningful HDR (more than ~18% brighter than SDR white).
        float vis = smoothstep(0.24, 0.32, log2(gain));
        c = mix(c, heat, vis * (0.25 + 0.35 * t));
    }

    if (u_showMask >= 0) c = mix(c, vec3(0.95, 0.2, 0.35), showW * 0.55);
    fragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
}
