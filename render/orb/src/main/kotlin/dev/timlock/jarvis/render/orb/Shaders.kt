package dev.timlock.jarvis.render.orb

/** GLSL ES 3.00 sources. All geometry is analytic in the fragment shader: no blur passes, no FBOs. */
internal object Shaders {
    private const val VS = "#version 300 es\nprecision highp float;\n"
    private const val FS = "#version 300 es\nprecision highp float;\nprecision highp int;\n"
    private const val NDC = "uniform vec2 uRes;\nvec4 ndc(vec2 p){return vec4(p.x/uRes.x*2.0-1.0,1.0-p.y/uRes.y*2.0,0.0,1.0);}\n"
    private const val TAU = "const float TAU=6.2831853;\n"

    /** Full ring / arc / dash strip. aTV = (theta, v). One shared VBO for every ring. */
    const val RING_VS = VS + NDC + TAU + """
layout(location=0) in vec2 aTV;
uniform vec2 uCenter; uniform float uRadius; uniform float uHalfW; uniform float uRot;
uniform float uDisp[32]; uniform float uDispOn; uniform vec3 uColor; uniform float uAlpha;
out float vV; out float vAng; out vec3 vCol; out float vA;
void main(){
  float rad = uRadius;
  if (uDispOn > 0.5) {
    float f = (aTV.x - uRot) / TAU; f = (f - floor(f)) * 32.0;
    int i0 = int(f); float t = f - float(i0); t = t*t*(3.0-2.0*t);
    rad += mix(uDisp[i0], uDisp[(i0+1)&31], t);
  }
  rad += aTV.y * uHalfW;
  vec2 p = uCenter + rad * vec2(sin(aTV.x), -cos(aTV.x));
  gl_Position = ndc(p);
  vV = aTV.y; vAng = aTV.x; vCol = uColor; vA = uAlpha;
}
"""

    /** Tick marks as quads. aT = (theta, along 0..1, side -1..1, index). */
    const val TICK_VS = VS + NDC + """
layout(location=0) in vec4 aT;
uniform vec2 uCenter; uniform float uRadius; uniform float uHalfW; uniform float uRot;
uniform float uLenS; uniform float uLenL; uniform float uLongEvery; uniform float uDir;
uniform vec3 uColS; uniform vec3 uColL; uniform vec3 uColSweep; uniform float uAlphaS; uniform float uAlphaL;
uniform float uSweepOn; uniform float uHead; uniform float uCount;
out float vV; out float vAng; out vec3 vCol; out float vA;
void main(){
  float th = aT.x + uRot;
  bool isLong = uLongEvery > 0.5 && mod(aT.w, uLongEvery) < 0.5;
  float len = isLong ? uLenL : uLenS;
  vec2 dir = vec2(sin(th), -cos(th)); vec2 tg = vec2(cos(th), sin(th));
  float rad = uRadius - uDir * aT.y * len;
  vec2 p = uCenter + dir * rad + tg * aT.z * uHalfW;
  gl_Position = ndc(p);
  vCol = isLong ? uColL : uColS; vA = isLong ? uAlphaL : uAlphaS;
  if (uSweepOn > 0.5) {
    float k = mod(uHead - aT.w, uCount);
    if (k < 8.0) { float s = 1.0 - k / 8.0; vCol = mix(vCol, uColSweep, s); vA = mix(vA, min(1.0, vA * 1.7), s); }
  }
  vV = aT.z; vAng = 0.0;
}
"""

    /** Straight strips (HUD brackets). aA = (x, y in R units, nx, ny), aV = side. */
    const val LINE_VS = VS + NDC + """
layout(location=0) in vec4 aA; layout(location=1) in float aV;
uniform vec2 uCenter; uniform float uR; uniform float uHalfW; uniform vec3 uColor; uniform float uAlpha;
out float vV; out float vAng; out vec3 vCol; out float vA;
void main(){
  vec2 p = uCenter + uR * aA.xy + aA.zw * aV * uHalfW;
  gl_Position = ndc(p);
  vV = aV; vAng = 0.0; vCol = uColor; vA = uAlpha;
}
"""

    /** Shared by ring, tick and line programs: solid core + exp glow, optional angular masks. */
    const val STROKE_FS = FS + TAU + """
in float vV; in float vAng; in vec3 vCol; in float vA;
uniform float uHalfW; uniform float uStroke; uniform float uGlow; uniform float uSigma;
uniform float uRot; uniform float uSoft; uniform float uCap; uniform float uRadPx;
uniform int uMode; uniform vec4 uArcs; uniform vec2 uPer;
out vec4 oC;
float arcM(float a, float st, float len, float per, out float cap){
  float q = mod(a - st, per);
  float inside = step(q, len);
  float m = smoothstep(0.0, uSoft, q) * (1.0 - smoothstep(len - uSoft, len, q));
  float e = min(q, len - q) * uRadPx;
  cap = uCap * exp(-e*e/24.0) * inside;
  return m;
}
void main(){
  float d = abs(vV) * uHalfW;
  float core = 1.0 - smoothstep(uStroke - 0.75, uStroke + 0.75, d);
  float g = 0.0;
  if (uGlow > 0.0) {
    float o = max(d - uStroke, 0.0);
    g = uGlow * exp(-(o*o) / (2.0*uSigma*uSigma)) * (1.0 - smoothstep(0.55, 1.0, d / uHalfW));
  }
  float m = 1.0; float cap = 0.0; float c2 = 0.0;
  float a = vAng - uRot;
  if (uMode == 1) {
    m = arcM(a, 0.0, uPer.y, uPer.x, cap);
  } else if (uMode == 2) {
    float m0 = arcM(a, uArcs.x, uArcs.y, TAU, cap);
    float m1 = arcM(a, uArcs.z, uArcs.w, TAU, c2);
    m = max(m0, m1); cap = max(cap * step(0.001, m0), c2 * step(0.001, m1));
  }
  float v = (core + g) * m * (1.0 + cap) * vA;
  vec3 c = vCol * v;
  float h = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
  c += (h - 0.5) / 255.0 * smoothstep(0.0, 0.05, g * m * vA);
  oC = vec4(c, 1.0);
}
"""

    /** Quad from gl_VertexID (triangle strip, 4 verts); used for vignette, grid and core sprites. */
    private const val QUAD = "vec2 quad(){return vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 2.0 - 1.0;}\n"

    const val VIGNETTE_VS = VS + QUAD + """
void main(){ gl_Position = vec4(quad(), 0.0, 1.0); }
"""
    const val VIGNETTE_FS = FS + """
uniform vec3 uDeep; uniform vec3 uVoid; uniform vec2 uCenterPx; uniform float uInv;
out vec4 oC;
void main(){
  float t = clamp(length(gl_FragCoord.xy - uCenterPx) * uInv, 0.0, 1.0);
  vec3 c = mix(uDeep, uVoid, t);
  float h = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
  c += (h - 0.5) / 255.0;
  oC = vec4(c, 1.0);
}
"""

    const val SPRITE_VS = VS + NDC + QUAD + """
uniform vec2 uCenter; uniform float uHalf;
out vec2 vUv; out vec2 vP;
void main(){ vec2 q = quad(); vUv = q * 0.5 + 0.5; vP = q; gl_Position = ndc(uCenter + q * uHalf); }
"""
    const val SPRITE_FS = FS + """
in vec2 vUv; uniform sampler2D uTex; uniform vec3 uTint; uniform vec3 uHot; uniform float uHotMix; uniform float uInt;
out vec4 oC;
void main(){
  float s = texture(uTex, vUv).r;
  vec3 c = mix(uTint, uHot, smoothstep(0.35, 0.95, s) * uHotMix);
  oC = vec4(c * s * uInt, 1.0);
}
"""

    const val GRID_FS = FS + TAU + """
in vec2 vP; uniform float uR; uniform float uRot; uniform vec3 uCol; uniform float uA;
uniform float uRadar; uniform float uRadarAng;
out vec4 oC;
void main(){
  float r = length(vP) * 1.6;
  if (r > 1.6) discard;
  float px = 1.0 / uR;
  float fr = r / 0.2; float k = floor(fr + 0.5);
  float dr = abs(fr - k) * 0.2;
  float ring = step(0.5, k) * step(k, 8.5) * clamp(1.5 - dr / px, 0.0, 1.0);
  float th = atan(vP.x, -vP.y);
  float seg = TAU / 24.0;
  float da = abs(mod(th - uRot + seg * 0.5, seg) - seg * 0.5);
  float rad = clamp(1.5 - da * r * uR, 0.0, 1.0) * smoothstep(0.08, 0.25, r);
  float lines = max(ring, rad * 0.8);
  float fade = 1.0 - smoothstep(1.3, 1.6, r);
  float v = lines * fade;
  if (uRadar > 0.0) {
    float behind = mod(uRadarAng - th, TAU);
    float wedge = exp(-behind * 2.2) * step(behind, 3.0) * (1.0 - r / 1.6);
    float edge = exp(-behind * behind * 400.0) * 1.2;
    v += uRadar * (lines * (wedge * 2.5) + (wedge * 0.22 + edge * 0.5 * (1.0 - r / 1.6)));
  }
  oC = vec4(uCol * v * uA, 1.0);
}
"""

    /** Labels: aA = (theta, x -1..1 tangent, y 0..1 radial, cell index). */
    const val LABEL_VS = VS + NDC + """
layout(location=0) in vec4 aA;
uniform vec2 uCenter; uniform float uRadius; uniform float uRot; uniform vec2 uHalfSize;
out vec2 vUv;
void main(){
  float th = aA.x + uRot;
  vec2 dir = vec2(sin(th), -cos(th)); vec2 tg = vec2(cos(th), sin(th));
  float lx = aA.y * uHalfSize.x; float ly = (aA.z - 0.5) * 2.0 * uHalfSize.y;
  if (dir.y > 0.0) { lx = -lx; ly = -ly; }
  vec2 p = uCenter + dir * uRadius + tg * lx + dir * ly;
  gl_Position = ndc(p);
  float col = mod(aA.w, 4.0); float row = floor(aA.w / 4.0);
  vUv = vec2((col + aA.y * 0.5 + 0.5) / 4.0, (row + (1.0 - aA.z)) / 3.0);
}
"""
    const val LABEL_FS = FS + """
in vec2 vUv; uniform sampler2D uTex; uniform vec3 uColor; uniform float uAlpha;
out vec4 oC;
void main(){ float a = texture(uTex, vUv).a; oC = vec4(uColor * a * uAlpha, 1.0); }
"""

    /** Particles: aA = (seed, orbitR, phase, speed deg/s), aB = (size px, depth, streamer, -). All motion here. */
    const val PART_VS = VS + NDC + """
layout(location=0) in vec4 aA; layout(location=1) in vec4 aB;
uniform vec2 uCenter; uniform float uR; uniform float uOrbit; uniform float uRadOff; uniform float uTime;
uniform float uCount; uniform float uDepthLo; uniform float uDepthHi; uniform float uBright; uniform float uPull;
uniform float uScale; uniform vec3 uTint; uniform vec3 uTeal; uniform vec3 uBrightCol;
out vec3 vC; out float vA;
void main(){
  float depth = aB.y;
  float vis = step(uDepthLo, depth) * (1.0 - step(uDepthHi, depth));
  float cf = clamp((uCount - float(gl_VertexID)) / 6.0, 0.0, 1.0);
  float lo = 0.35; float hi = 1.45; float span = hi - lo;
  float r = lo + mod(aA.y - lo + uRadOff, span);
  float ef = smoothstep(0.0, 0.09, r - lo) * smoothstep(0.0, 0.09, hi - r);
  r = lo + (r - lo) * uPull;
  r += 0.018 * sin(uTime * (0.5 + aA.x) + aA.x * 40.0);
  float ang = aA.z + aA.w * uOrbit * 0.0174533;
  vec2 p = uCenter + r * uR * vec2(sin(ang), -cos(ang));
  float a = uBright * (0.35 + 0.65 * depth) * (1.0 + aB.z) * ef * cf * vis;
  if (a < 0.002) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); gl_PointSize = 0.0; vA = 0.0; vC = vec3(0.0); return; }
  gl_Position = ndc(p);
  gl_PointSize = aB.x * uScale * (0.75 + 0.5 * depth) + 1.5;
  vC = mix(mix(uTeal, uBrightCol, depth), uTint, 0.5);
  vA = a;
}
"""
    const val PART_FS = FS + """
in vec3 vC; in float vA; out vec4 oC;
void main(){
  float d = length(gl_PointCoord - 0.5) * 2.0;
  float s = exp(-d * d * 3.0) * (1.0 - smoothstep(0.75, 1.0, d));
  oC = vec4(vC * s * vA, 1.0);
}
"""

    /** Leader line: one quad along (uP0, uDir) for uLc px (+uPad), vS = px along, vD = px across. Analytic, no blur. */
    const val LEAD_VS = VS + NDC + QUAD + """
uniform vec2 uP0; uniform vec2 uDir; uniform float uLc; uniform float uPad;
out float vS; out float vD;
void main(){
  vec2 q = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  float s = mix(-uPad, uLc + uPad, q.x); float d = mix(-uPad, uPad, q.y);
  vS = s; vD = d;
  gl_Position = ndc(uP0 + uDir * s + vec2(-uDir.y, uDir.x) * d);
}
"""
    const val LEAD_FS = FS + """
in float vS; in float vD;
uniform float uLc; uniform float uHalf; uniform float uGlowSig; uniform float uAlpha;
uniform vec3 uCol; uniform vec3 uHot;
uniform float uHeadAmt; uniform float uHeadSig; uniform float uPulseS; uniform float uPulseAmt; uniform float uPulseSig;
out vec4 oC;
void main(){
  float d = abs(vD);
  float core = 1.0 - smoothstep(uHalf - 0.5, uHalf + 0.5, d);
  float o = max(d - uHalf, 0.0);
  float g = 0.3 * exp(-(o*o) / (2.0*uGlowSig*uGlowSig));
  float m = smoothstep(-0.5, 0.5, vS) * (1.0 - smoothstep(uLc - 0.5, uLc + 0.5, vS));
  vec3 c = uCol * (core + g) * m * uAlpha;
  float hr = (vS - uLc) * (vS - uLc) + vD * vD;
  c += uHot * uHeadAmt * exp(-hr / (2.0 * uHeadSig * uHeadSig));
  float pr = (vS - uPulseS) * (vS - uPulseS) + vD * vD;
  c += uHot * uPulseAmt * exp(-pr / (2.0 * uPulseSig * uPulseSig));
  oC = vec4(c, 1.0);
}
"""
}
