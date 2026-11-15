package brew.lab

/**
 * AGSL sources of the Lab screen. Plain strings: the document carries the shader source, the player compiles it on the GPU (Android:
 * RuntimeShader, API 33+; iOS rc-players: Skia RuntimeEffect). Every shader takes the same two uniforms the document feeds each frame —
 * iResolution (the canvas size) and iTime (seconds, continuous) — plus iTouch (the finger, in canvas units) where it reacts to touch.
 *
 * The app allows exactly these sources and nothing else (RemoteComposePlayer.setShaderControl): shaders stay off for any other document.
 */
object LabShaders {
    /**
     * Strips indentation and blank lines: a document's text is read back at most 4000 bytes at a time ("attempt read a buff of invalid size
     * … > 4000"), so a long shader must stay under that.
     */
    private fun compact(src: String): String = src.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** Liquid latte: domain-warped fractal noise in espresso / caramel / cream, slowly swirling. */
    val LATTE =
        compact(
            """
uniform float2 iResolution;
uniform float iTime;
float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p); float2 f = fract(p); float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0; float a = 0.5;
    for (int i = 0; i < 5; i++) { v += a * noise(p); p = p * 2.02 + float2(1.7, 9.2); a *= 0.5; }
    return v;
}
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution.y;
    float t = iTime * 0.18;
    float2 q = float2(fbm(uv * 2.6 + t), fbm(uv * 2.6 + float2(5.2, 1.3) - t));
    float2 r = float2(fbm(uv * 2.6 + 4.0 * q + float2(1.7, 9.2) + 0.7 * t),
                      fbm(uv * 2.6 + 4.0 * q + float2(8.3, 2.8) - 0.6 * t));
    float f = fbm(uv * 2.6 + 4.0 * r);
    float3 espresso = float3(0.13, 0.07, 0.04);
    float3 caramel = float3(0.80, 0.50, 0.25);
    float3 cream = float3(0.99, 0.93, 0.84);
    float3 col = mix(espresso, caramel, clamp(f * f * 2.4, 0.0, 1.0));
    col = mix(col, cream, clamp(length(q) * 0.9 - 0.3, 0.0, 1.0) * smoothstep(0.5, 0.95, f));
    col += 0.35 * pow(clamp(r.x * 1.1, 0.0, 1.0), 7.0);
    float2 v = fragCoord / iResolution - 0.5;
    col *= 1.0 - 0.45 * dot(v, v);
    return half4(half3(col), 1.0);
}
"""
        )

    /** Coffee ripple: a tap drops a ring wave into the cup (iTouch, iTouchTime); idle, a drop falls every 2.5 s. */
    val RIPPLE =
        compact(
            """
uniform float2 iResolution;
uniform float iTime;
uniform float2 iTouch;
uniform float iTouchTime;
uniform float iAnim;
float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p); float2 f = fract(p); float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float wave(float2 uv, float2 c, float age) {
    // !(a && b), not (a || b): a NaN age (no touch yet on some players) must also return 0.
    if (!(age >= 0.0 && age <= 4.0)) return 0.0;
    float d = length(uv - c);
    float front = age * 0.42;
    return sin((d - front) * 52.0) * exp(-abs(d - front) * 12.0) * exp(-age * 0.85);
}
float heightAt(float2 uv) {
    float2 res = iResolution / iResolution.y;
    float slot = floor(iTime / 2.5);
    float2 c = float2(fract(sin(slot * 12.9898) * 43758.5) * res.x, fract(sin(slot * 78.233) * 43758.5) * res.y);
    float h = wave(uv, c, iTime - slot * 2.5);
    if (iTouch.x > 0.0) { h += 1.6 * wave(uv, iTouch / iResolution.y, iAnim - iTouchTime); }
    return h;
}
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution.y;
    float e = 0.004;
    float h = heightAt(uv);
    float2 grad = float2(heightAt(uv + float2(e, 0.0)) - h, heightAt(uv + float2(0.0, e)) - h) / e;
    float2 p = uv + grad * 0.0035;
    float2 res = iResolution / iResolution.y;
    float crema = 0.55 * noise(p * 3.5 + iTime * 0.04) + 0.3 * noise(p * 7.0 - iTime * 0.03) + 0.15 * noise(p * 14.0);
    float rim = length(p - 0.5 * res) / (0.5 * res.y);
    float3 coffee = mix(float3(0.12, 0.06, 0.03), float3(0.62, 0.38, 0.20), smoothstep(0.25, 0.95, crema) * 0.75);
    coffee = mix(coffee, float3(0.80, 0.58, 0.36), smoothstep(0.75, 1.35, rim) * 0.6);
    float3 n = normalize(float3(-grad * 0.012, 1.0));
    float spec = pow(clamp(dot(n, normalize(float3(-0.4, -0.5, 1.0))), 0.0, 1.0), 60.0);
    float3 col = coffee + spec * float3(1.0, 0.95, 0.85) * 1.6 + h * 0.10;
    float2 v = fragCoord / iResolution - 0.5;
    col *= 1.0 - 0.5 * dot(v, v);
    return half4(half3(col), 1.0);
}
"""
        )

    /** Holo card: rainbow foil, diffraction lines and sparkles; the glare follows the finger (else it sways). */
    val HOLO =
        compact(
            """
uniform float2 iResolution;
uniform float iTime;
uniform float2 iTouch;
float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }
float3 spectrum(float x) { return 0.5 + 0.5 * cos(6.28318 * (x + float3(0.0, 0.33, 0.67))); }
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float2 sway = float2(0.32 * sin(iTime * 0.9), 0.22 * cos(iTime * 0.7));
    float2 tilt = iTouch.x > 0.0 ? clamp(iTouch / iResolution, 0.0, 1.0) - 0.5 + 0.25 * sway : sway;
    float band = uv.x * 0.9 + uv.y * 1.3 + tilt.x * 1.8 + tilt.y * 1.2;
    float3 holo = spectrum(band * 0.9 + iTime * 0.04);
    float lines = 0.5 + 0.5 * sin((uv.x + uv.y) * 420.0 + tilt.x * 50.0);
    float2 g = float2(0.5) + tilt * 1.3;
    float glare = exp(-10.0 * dot(uv - g, uv - g));
    float s = hash(floor(fragCoord / 5.0));
    float twinkle = step(0.982, s) * pow(0.5 + 0.5 * sin(iTime * 5.0 + s * 60.0 + tilt.x * 25.0), 6.0);
    float3 base = float3(0.05, 0.06, 0.12);
    float3 pearl = mix(holo, float3(0.92, 0.94, 1.0), 0.28);
    float3 col = mix(base, pearl, 0.62 + 0.06 * lines);
    col = col * 0.72 + glare * float3(1.0, 0.97, 0.9) * 0.95 + twinkle * 1.6;
    float2 v = uv - 0.5;
    col *= 1.0 - 0.55 * dot(v, v);
    return half4(half3(col), 1.0);
}
"""
        )

    /**
     * Lenticular 3D flip card, rendered as a physical card: a plane in perspective that turns around its vertical axis with the finger
     * (±30°) over a soft shadow. Each side is three layers — a background drawn here, a middle layer and a front layer (images) — shifted
     * by different amounts as the card turns: that parallax is the depth. ~70 ribbed lenses decide which side each strip shows, so the flip
     * sweeps across the card with a rainbow fringe; a gloss band slides over the plastic and the ridges catch the light in rainbow colours.
     * Only the finger moves it: untouched, it rests slightly turned on side A.
     */
    val LENTICULAR =
        compact(
            """
uniform float2 iResolution;
uniform float2 iTouch;
uniform shader aMid;
uniform shader aFront;
uniform shader bMid;
uniform shader bFront;
uniform float2 imgSize;
float sdBox(float2 p, float2 b, float r) { float2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }
float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float inside(float2 uv) { return step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0); }
float3 over(float3 under, half4 top) { return float3(top.rgb) + under * (1.0 - float(top.a)); }
float3 sideA(float2 uv, float a) {
    float2 b = uv + float2(-a * 0.06, 0.0);
    float3 col = mix(float3(1.0, 0.72, 0.30), float3(0.46, 0.06, 0.03), b.y);
    float2 q = b - float2(0.5, 0.44);
    float rays = smoothstep(0.2, 0.5, 0.5 + 0.5 * sin(atan(q.y, q.x) * 9.0));
    col += rays * 0.10 * (1.0 - b.y);
    col += float3(1.0, 0.9, 0.6) * 0.35 * exp(-dot(q, q) * 9.0);
    float2 um = uv + float2(a * 0.02, 0.0);
    float2 uf = uv + float2(a * 0.075, 0.0);
    col = over(col, aMid.eval(clamp(um, 0.0, 1.0) * imgSize) * half(inside(um)));
    col = over(col, aFront.eval(clamp(uf, 0.0, 1.0) * imgSize) * half(inside(uf)));
    return col;
}
float3 sideB(float2 uv, float a) {
    float2 b = uv + float2(-a * 0.06, 0.0);
    float3 col = mix(float3(0.58, 0.90, 1.0), float3(0.03, 0.15, 0.45), b.y);
    float2 cell = floor(b * float2(9.0, 11.0));
    float2 f = fract(b * float2(9.0, 11.0)) - 0.5;
    float h = hash(cell);
    float2 jit = float2(hash(cell + 3.1), hash(cell + 7.7)) - 0.5;
    float rad = 0.05 + 0.10 * hash(cell + 1.3);
    col += float3(0.85, 0.97, 1.0) * smoothstep(rad + 0.03, rad, length(f - jit * 0.5)) * step(0.72, h) * 0.28;
    float2 q = b - float2(0.5, 0.44);
    col += float3(0.8, 0.95, 1.0) * 0.30 * exp(-dot(q, q) * 9.0);
    float2 um = uv + float2(a * 0.02, 0.0);
    float2 uf = uv + float2(a * 0.075, 0.0);
    col = over(col, bMid.eval(clamp(um, 0.0, 1.0) * imgSize) * half(inside(um)));
    col = over(col, bFront.eval(clamp(uf, 0.0, 1.0) * imgSize) * half(inside(uf)));
    return col;
}
float3 spectrum(float x) { return 0.5 + 0.5 * cos(6.28318 * (x + float3(0.0, 0.33, 0.67))); }
half4 main(float2 fragCoord) {
    float a = iTouch.x > 0.0 ? clamp(iTouch.x / iResolution.x * 2.2 - 1.1, -1.0, 1.0) : -0.45;
    float th = a * 0.52;
    float2 c = 0.5 * iResolution + float2(0.0, -6.0);
    float2 p = fragCoord - c;
    float hw = iResolution.x * 0.38;
    float hh = iResolution.y * 0.38;
    float f = iResolution.x * 1.6;
    float3 bg = mix(float3(0.10, 0.10, 0.12), float3(0.03, 0.03, 0.04), fragCoord.y / iResolution.y);
    float2 sp = p - float2(-a * 18.0, 24.0);
    bg *= 1.0 - 0.6 * smoothstep(50.0, -16.0, sdBox(sp, float2(hw * cos(th), hh), 20.0));
    float denom = f * cos(th) - p.x * sin(th);
    if (denom <= 1.0) return half4(half3(bg), 1.0);
    float xc = p.x * f / denom;
    float yc = p.y * (f + xc * sin(th)) / f;
    float d = sdBox(float2(xc, yc), float2(hw, hh), hw * 0.07);
    float alpha = smoothstep(1.2, -0.6, d);
    if (alpha <= 0.0) return half4(half3(bg), 1.0);
    float2 uv = (float2(xc, yc) + float2(hw, hh)) / (2.0 * float2(hw, hh));
    float lens = fract(uv.x * 70.0);
    float3 A = sideA(uv, a);
    float3 B = sideB(uv, a);
    float view = a * 1.6 + (lens - 0.5) * 0.3 + (uv.x - 0.5) * 0.22;
    float3 s = float3(smoothstep(-0.09, 0.09, view + 0.04), smoothstep(-0.09, 0.09, view), smoothstep(-0.09, 0.09, view - 0.04));
    float3 col = mix(A, B, s);
    float ridge = sin(lens * 3.14159);
    col *= 0.88 + 0.14 * ridge;
    col *= 1.0 - 0.22 * max(sin(th), 0.0) + 0.05 * max(-sin(th), 0.0);
    float gx = 0.5 - a * 0.95;
    float gloss = exp(-pow((uv.x - gx) * 3.6 - (uv.y - 0.5) * 1.3, 2.0));
    float spark = pow(ridge, 10.0);
    col += gloss * (0.28 + 0.45 * spark);
    col += spectrum(uv.x * 1.5 + a * 1.8 + lens * 0.15) * spark * (0.10 + 0.25 * gloss);
    float bevel = smoothstep(-3.5, -0.5, d);
    col = mix(col, float3(1.0), bevel * 0.4);
    col = mix(bg, col, alpha);
    return half4(half3(col), 1.0);
}
"""
        )

    /** Lava lamp: seven metaballs with a hot core and a soft glow. */
    val LAVA =
        compact(
            """
uniform float2 iResolution;
uniform float iTime;
half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * iResolution) / iResolution.y;
    float t = iTime * 0.55;
    float f = 0.0;
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float2 c = float2(0.48 * sin(t * (0.37 + 0.13 * fi) + fi * 1.7), 0.30 * sin(t * (0.31 + 0.11 * fi) + fi * 2.3));
        float r = 0.15 + 0.04 * sin(t * 1.3 + fi);
        f += r * r / max(dot(uv - c, uv - c), 0.0001);
    }
    float3 bg = mix(float3(0.06, 0.01, 0.10), float3(0.20, 0.04, 0.26), fragCoord.y / iResolution.y);
    float glow = clamp(f * 0.2, 0.0, 1.0);
    float3 col = bg + glow * glow * float3(1.0, 0.3, 0.55) * 0.9;
    float m = smoothstep(0.92, 1.08, f);
    float3 blob = mix(float3(0.98, 0.18, 0.55), float3(1.0, 0.62, 0.18), clamp((f - 1.0) * 0.35, 0.0, 1.0));
    blob += 0.35 * pow(clamp((f - 1.0) / 4.0, 0.0, 1.0), 2.0);
    col = mix(col, blob, m);
    return half4(half3(col), 1.0);
}
"""
        )

    /** Aurora: green-violet curtains over a mountain ridge and a starry sky. */
    val AURORA =
        compact(
            """
uniform float2 iResolution;
uniform float iTime;
float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }
float noise(float2 p) {
    float2 i = floor(p); float2 f = fract(p); float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float y = 1.0 - uv.y;
    float t = iTime * 0.25;
    float3 col = mix(float3(0.01, 0.015, 0.05), float3(0.02, 0.07, 0.14), y);
    float star = step(0.996, hash(floor(fragCoord / 1.5))) * (0.6 + 0.4 * sin(iTime * 3.0 + hash(floor(fragCoord)) * 30.0));
    col += star * 0.9 * smoothstep(0.35, 0.7, y);
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float x = uv.x * (2.0 + fi * 0.7);
        float base = 0.42 + 0.08 * fi + 0.07 * sin(x * 2.1 + t * 1.3 + fi) + 0.05 * noise(float2(x * 2.0 + t, fi * 3.0));
        float curtain = pow(noise(float2(x * 6.0 + t * 2.0, fi * 7.0 + t * 0.5)), 1.6);
        float d = y - base;
        float a = smoothstep(-0.02, 0.02, d) * exp(-max(d, 0.0) * (5.0 - fi)) * curtain;
        float3 tint = mix(float3(0.15, 1.0, 0.55), float3(0.65, 0.35, 1.0), clamp(d * 3.0 + fi * 0.3, 0.0, 1.0));
        col += tint * a * 1.25;
    }
    float ridge = 0.16 + 0.07 * sin(uv.x * 7.0) + 0.04 * sin(uv.x * 19.0 + 1.3) + 0.02 * noise(float2(uv.x * 40.0, 0.0));
    col = mix(col, float3(0.0, 0.005, 0.015), smoothstep(ridge + 0.004, ridge - 0.004, y));
    return half4(half3(col), 1.0);
}
"""
        )

    /** Hyperspace: stars streaking out of a glowing tunnel. */
    val HYPERSPACE =
        compact(
            """
uniform float2 iResolution;
uniform float iTime;
float hash(float2 p) { return fract(sin(dot(p, float2(41.3, 289.1))) * 43758.5453); }
half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * iResolution) / iResolution.y;
    float r = length(uv);
    float a = atan(uv.y, uv.x);
    float3 col = float3(0.01, 0.015, 0.05) + float3(0.25, 0.35, 0.9) * exp(-r * 7.0);
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float sectors = 48.0 + fi * 24.0;
        float sector = floor((a / 6.28318 + 0.5) * sectors);
        float z = 0.35 / max(r, 0.002) + iTime * (1.4 + fi * 0.5) + hash(float2(sector, fi)) * 10.0;
        float cell = floor(z);
        float h = hash(float2(sector, cell + fi * 17.0));
        float along = fract(z);
        float star = step(0.55, h) * smoothstep(0.0, 0.08, along) * smoothstep(0.6, 0.1, along);
        float width = abs(fract((a / 6.28318 + 0.5) * sectors) - 0.5);
        star *= smoothstep(0.5, 0.0, width * 2.0) * clamp(r * 2.2, 0.0, 1.2);
        col += star * mix(float3(0.55, 0.75, 1.0), float3(1.0, 0.85, 1.0), h);
    }
    return half4(half3(col), 1.0);
}
"""
        )

    val ALL = listOf(LATTE, RIPPLE, HOLO, LENTICULAR, LAVA, AURORA, HYPERSPACE)

    init {
        ALL.forEach { require(it.encodeToByteArray().size < 4000) { "shader over the 4000-byte text limit: ${it.length}" } }
    }
}
