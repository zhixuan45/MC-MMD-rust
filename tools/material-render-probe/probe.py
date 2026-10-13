"""Headless Linux/Mesa check of the production Toon shaders' alpha/depth output.

Run: python tools/material-render-probe/probe.py
Uses system EGL/OpenGL through ctypes; no Python dependencies or model assets.
"""
import ctypes as C
import os
from pathlib import Path

os.environ.setdefault("MESA_SHADER_CACHE_DIR", "/tmp/mmd-material-shader-cache")


def bind(lib, name, result, *args):
    fn = getattr(lib, name)
    fn.restype, fn.argtypes = result, args
    return fn


egl = C.CDLL("libEGL.so.1")
gl = C.CDLL("libGL.so.1")
ptr, integer, uint, floating = C.c_void_p, C.c_int, C.c_uint, C.c_float
get_display = bind(egl, "eglGetPlatformDisplay", ptr, uint, ptr, ptr)
display = get_display(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
assert bind(egl, "eglInitialize", uint, ptr, ptr, ptr)(display, None, None)
assert bind(egl, "eglBindAPI", uint, uint)(0x30A2)  # EGL_OPENGL_API
config = ptr()
count = integer()
attributes = (integer * 15)(0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8,
                            0x3022, 8, 0x3021, 8, 0x3025, 24, 0x3038)
assert bind(egl, "eglChooseConfig", uint, ptr, ptr, ptr, integer, ptr)(
    display, attributes, C.byref(config), 1, C.byref(count)) and count.value == 1
surface = bind(egl, "eglCreatePbufferSurface", ptr, ptr, ptr, ptr)(
    display, config, (integer * 5)(0x3057, 1, 0x3056, 1, 0x3038))
context = bind(egl, "eglCreateContext", ptr, ptr, ptr, ptr, ptr)(
    display, config, None, (integer * 5)(0x3098, 3, 0x30FB, 3, 0x3038))
assert surface and context
assert bind(egl, "eglMakeCurrent", uint, ptr, ptr, ptr, ptr)(display, surface, surface, context)

root = Path(__file__).resolve().parents[2]
shaders = root / "common/src/main/resources/assets/mmdskin/shader"
create_shader = bind(gl, "glCreateShader", uint, uint)
shader_source = bind(gl, "glShaderSource", None, uint, integer, ptr, ptr)
compile_shader = bind(gl, "glCompileShader", None, uint)
shader_status = bind(gl, "glGetShaderiv", None, uint, uint, ptr)
shader_log = bind(gl, "glGetShaderInfoLog", None, uint, integer, ptr, ptr)


def compile_source(kind, source):
    shader = create_shader(kind)
    text = C.c_char_p(source.encode())
    shader_source(shader, 1, C.byref(text), None)
    compile_shader(shader)
    ok = integer()
    shader_status(shader, 0x8B81, C.byref(ok))
    log = C.create_string_buffer(8192)
    shader_log(shader, len(log), None, log)
    assert ok.value, log.value.decode()
    return shader


fragment = (shaders / "toon_main_body.frag.glsl").read_text()
fragment = fragment.replace("/* TOON_OUTPUT_DECLARATIONS */", "layout(location=0) out vec4 result;")
fragment = fragment.replace("/* TOON_OUTPUT_WRITER */",
    "void writeToonOutputs(vec3 c, vec3 a, vec3 n, float opacity) {result=vec4(c,opacity);}")
fragment = fragment.replace("/* TOON_SURFACE_LIGHTING */",
    (shaders / "toon_surface_lighting.glsl").read_text())
fragment = fragment.replace("/* TOON_LIGHTING */",
    "vec3 applyPackLighting(vec3 raw, vec3 n, vec3 p) { return raw; }")
program = bind(gl, "glCreateProgram", uint)()
attach = bind(gl, "glAttachShader", None, uint, uint)
attach(program, compile_source(0x8B31, (shaders / "toon_main_body.vert.glsl").read_text()))
attach(program, compile_source(0x8B30, fragment))
bind(gl, "glLinkProgram", None, uint)(program)
ok = integer()
bind(gl, "glGetProgramiv", None, uint, uint, ptr)(program, 0x8B82, C.byref(ok))
assert ok.value, "Toon shader link failed"
bind(gl, "glUseProgram", None, uint)(program)
location = bind(gl, "glGetUniformLocation", integer, uint, C.c_char_p)
u1f = bind(gl, "glUniform1f", None, integer, floating)
u1i = bind(gl, "glUniform1i", None, integer, integer)
u3f = bind(gl, "glUniform3f", None, integer, floating, floating, floating)
u4f = bind(gl, "glUniform4f", None, integer, floating, floating, floating, floating)


def loc(name):
    return location(program, name.encode())


identity = (floating * 16)(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)
matrix = bind(gl, "glUniformMatrix4fv", None, integer, integer, C.c_ubyte, ptr)
for name in ("ProjMat", "ModelViewMat"):
    transform = (floating * 16)(*identity)
    transform[14] = .5 if name == "ProjMat" else -.5
    matrix(loc(name), 1, False, transform)
u1f(loc("LightIntensity"), 1)
u1f(loc("AlphaCutoff"), .001)
u1f(loc("SpecularIntensity"), 1)
u1f(loc("SpecularPower"), 96)
u1f(loc("MaterialSpecularPower"), 5)
u1i(loc("ToonLevels"), 2)
u3f(loc("LightDir"), 0, 0, 1)
u3f(loc("ShadowColor"), 1, 1, 1)
u3f(loc("MaterialSpecular"), 0, 0, 0)
u1i(loc("Sampler0"), 0)
vao = uint()
bind(gl, "glGenVertexArrays", None, integer, ptr)(1, C.byref(vao))
bind(gl, "glBindVertexArray", None, uint)(vao)
vbo = uint()
bind(gl, "glGenBuffers", None, integer, ptr)(1, C.byref(vbo))
bind(gl, "glBindBuffer", None, uint, uint)(0x8892, vbo)
data = (floating * 9)(-1, -1, 0, 3, -1, 0, -1, 3, 0)
bind(gl, "glBufferData", None, uint, C.c_ssize_t, ptr, uint)(0x8892, C.sizeof(data), data, 0x88E4)
bind(gl, "glEnableVertexAttribArray", None, uint)(0)
bind(gl, "glVertexAttribPointer", None, uint, integer, uint, C.c_ubyte, integer, ptr)(0, 3, 0x1406, False, 0, None)
bind(gl, "glVertexAttrib3f", None, uint, floating, floating, floating)(1, 0, 0, 1)
bind(gl, "glVertexAttrib2f", None, uint, floating, floating)(2, .5, .5)
tex = uint()
bind(gl, "glGenTextures", None, integer, ptr)(1, C.byref(tex))
bind(gl, "glBindTexture", None, uint, uint)(0x0DE1, tex)
image = bind(gl, "glTexImage2D", None, uint, integer, integer, integer, integer, integer, uint, uint, ptr)
for parameter in (0x2801, 0x2800):
    bind(gl, "glTexParameteri", None, uint, uint, integer)(0x0DE1, parameter, 0x2600)
enable = bind(gl, "glEnable", None, uint)
enable(0x0BE2)
enable(0x0B71)
bind(gl, "glBlendFunc", None, uint, uint)(0x0302, 0x0303)
bind(gl, "glViewport", None, integer, integer, integer, integer)(0, 0, 1, 1)
clear_color = bind(gl, "glClearColor", None, floating, floating, floating, floating)
clear = bind(gl, "glClear", None, uint)
depth_mask = bind(gl, "glDepthMask", None, C.c_ubyte)
draw = bind(gl, "glDrawArrays", None, uint, integer, integer)
read = bind(gl, "glReadPixels", None, integer, integer, integer, integer, uint, uint, ptr)


def check(label, material_alpha, texture_alpha, pass_number, expected, expected_depth,
          color=(0, 0, 0), eye_mask=0, emission=0, specular=(0, 0, 0)):
    image(0x0DE1, 0, 0x1908, 1, 1, 0, 0x1908, 0x1401,
          (C.c_ubyte * 4)(255, 255, 255, texture_alpha))
    depth_mask(True)
    clear_color(.4, .2, .8, 1)  # purple iris behind a black lash
    clear(0x4000 | 0x0100)
    depth_mask(pass_number == 1)
    u4f(loc("MaterialColor"), *color, material_alpha)
    u3f(loc("MaterialSpecular"), *specular)
    u1f(loc("EyeEmission"), emission)
    u1f(loc("EyeMaterialMask"), eye_mask)
    u1i(loc("AlphaPass"), pass_number)
    draw(0x0004, 0, 3)
    pixel = (C.c_ubyte * 4)()
    read(0, 0, 1, 1, 0x1908, 0x1401, pixel)
    depth = floating()
    read(0, 0, 1, 1, 0x1902, 0x1406, C.byref(depth))
    assert all(abs(pixel[i] / 255 - expected[i]) < .012 for i in range(3)), (label, list(pixel))
    assert abs(depth.value - expected_depth) < 1e-5, (label, depth.value)
    print("PASS:", label)


check("material opacity blends iris without writing depth", .4, 255, 2, (.24, .12, .48), 1)
check("opaque lash writes depth", 1, 255, 1, (0, 0, 0), .5)
check("translucent texel excluded from opaque pass", 1, 128, 1, (.4, .2, .8), 1)
check("texture opacity and material opacity multiply", .4, 128, 2,
      tuple(c * (1 - .4 * 128 / 255) for c in (.4, .2, .8)), 1)
check("opaque texel excluded from translucent pass", 1, 255, 2, (.4, .2, .8), 1)
check("low opacity survives previous 0.1 cutoff", .05, 255, 2, (.38, .19, .76), 1)
check("zero opacity is discarded", 0, 255, 2, (.4, .2, .8), 1)
check("eye emission retained with material color", 1, 255, 1,
      tuple(c * 2 ** (1 / 2.2) for c in (.2, .1, .4)), .5,
      color=(.2, .1, .4), eye_mask=1, emission=1)
check("non-eye material does not receive eye emission", 1, 255, 1, (.2, .1, .4), .5,
      color=(.2, .1, .4), eye_mask=-1, emission=1)
check("PMX specular color is used by shared surface lighting", 1, 255, 1, (.2, .1, .4), .5,
      specular=(.2, .1, .4))

outline = (shaders / "toon_outline_body.frag.glsl").read_text()
outline = outline.replace("/* TOON_OUTPUT_DECLARATIONS */", "layout(location=0) out vec4 outlineColor;")
outline = outline.replace("/* TOON_OUTPUT_WRITER */",
    "void writeToonOutputs(vec3 c, vec3 a, vec3 n, float opacity) {outlineColor=vec4(c,opacity);}")
outline = outline.replace("/* TOON_SURFACE_LIGHTING */",
    (shaders / "toon_surface_lighting.glsl").read_text())
outline = outline.replace("/* TOON_LIGHTING */",
    "vec3 applyPackLighting(vec3 raw, vec3 n, vec3 p) { return raw; }")
outline_program = bind(gl, "glCreateProgram", uint)()
attach(outline_program, compile_source(0x8B31, (shaders / "toon_outline_body.vert.glsl").read_text()))
attach(outline_program, compile_source(0x8B30, outline))
bind(gl, "glLinkProgram", None, uint)(outline_program)
bind(gl, "glGetProgramiv", None, uint, uint, ptr)(outline_program, 0x8B82, C.byref(ok))
assert ok.value, "Outline shader link failed"
print("PASS: shared surface lighting links for outline without material uniforms")
assert bind(gl, "glGetError", uint)() == 0
