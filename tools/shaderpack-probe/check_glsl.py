"""Linux optional probe: compile/link production GLSL and read G-buffer values through EGL/Mesa."""
import ctypes as ct
import math
import pathlib
import re
import sys

PTR, INT, UINT = ct.c_void_p, ct.c_int, ct.c_uint
EGL = ct.CDLL("libEGL.so.1")
GL = ct.CDLL("libGL.so.1")


def function(lib, name, result, arguments):
    value = getattr(lib, name)
    value.restype, value.argtypes = result, arguments
    return value


def egl(name, result, arguments):
    return function(EGL, name, result, arguments)


def gl(name, result, arguments):
    return function(GL, name, result, arguments)


get_proc = egl("eglGetProcAddress", PTR, [ct.c_char_p])
get_display = ct.CFUNCTYPE(PTR, UINT, PTR, ct.POINTER(INT))(get_proc(b"eglGetPlatformDisplayEXT"))
display = get_display(0x31DD, None, None)  # EGL_PLATFORM_SURFACELESS_MESA
major, minor = INT(), INT()
assert egl("eglInitialize", UINT, [PTR, ct.POINTER(INT), ct.POINTER(INT)])(display, ct.byref(major), ct.byref(minor))
assert egl("eglBindAPI", UINT, [UINT])(0x30A2)  # OpenGL
config, count = PTR(), INT()
attributes = (INT * 11)(0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3038)
assert egl("eglChooseConfig", UINT, [PTR, ct.POINTER(INT), ct.POINTER(PTR), INT, ct.POINTER(INT)])(
    display, attributes, ct.byref(config), 1, ct.byref(count)
)
context = egl("eglCreateContext", PTR, [PTR, PTR, PTR, ct.POINTER(INT)])(
    display, config, None, (INT * 7)(0x3098, 3, 0x30FB, 3, 0x30FD, 2, 0x3038)
)
assert context
surface = egl("eglCreatePbufferSurface", PTR, [PTR, PTR, ct.POINTER(INT)])(
    display, config, (INT * 5)(0x3057, 16, 0x3056, 16, 0x3038)
)
assert surface
assert egl("eglMakeCurrent", UINT, [PTR, PTR, PTR, PTR])(display, surface, surface, context)
print("OpenGL:", gl("glGetString", ct.c_char_p, [UINT])(0x1F02).decode(), flush=True)


def shader(source, kind, label):
    handle = gl("glCreateShader", UINT, [UINT])(kind)
    data = ct.c_char_p(source.encode())
    gl("glShaderSource", None, [UINT, INT, ct.POINTER(ct.c_char_p), PTR])(handle, 1, ct.byref(data), None)
    gl("glCompileShader", None, [UINT])(handle)
    ok = INT()
    gl("glGetShaderiv", None, [UINT, UINT, ct.POINTER(INT)])(handle, 0x8B81, ct.byref(ok))
    if not ok.value:
        log = ct.create_string_buffer(65536)
        gl("glGetShaderInfoLog", None, [UINT, INT, PTR, ct.c_char_p])(handle, len(log), None, log)
        raise AssertionError(f"{label}\n{log.value.decode()}")
    return handle


def program(vertex, fragment, label):
    handle = gl("glCreateProgram", UINT, [])()
    stages = [shader(vertex, 0x8B31, label), shader(fragment, 0x8B30, label)]
    for stage in stages:
        gl("glAttachShader", None, [UINT, UINT])(handle, stage)
    gl("glLinkProgram", None, [UINT])(handle)
    for stage in stages:
        gl("glDeleteShader", None, [UINT])(stage)
    ok = INT()
    gl("glGetProgramiv", None, [UINT, UINT, ct.POINTER(INT)])(handle, 0x8B82, ct.byref(ok))
    if not ok.value:
        log = ct.create_string_buffer(65536)
        gl("glGetProgramInfoLog", None, [UINT, INT, PTR, ct.c_char_p])(handle, len(log), None, log)
        raise AssertionError(f"{label}\n{log.value.decode()}")
    return handle


location = gl("glGetUniformLocation", INT, [UINT, ct.c_char_p])
delete_program = gl("glDeleteProgram", None, [UINT])
use_program = gl("glUseProgram", None, [UINT])
# 90 degree view-to-world rotation. Detects accidental view-space normal output.
view_to_world = (ct.c_float * 16)(0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)
triangle = "#version 330 core\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0.0,1.0);}"


def read_outputs(path, profile, buffers):
    source = path.read_text()
    handle = program(triangle, source, str(path))
    use_program(handle)
    gl("glUniformMatrix4fv", None, [INT, INT, UINT, PTR])(
        location(handle, b"ToonViewToWorld"), 1, 0, view_to_world
    )
    gl("glUniform1f", None, [INT, ct.c_float])(location(handle, b"ToonFar"), 256)
    framebuffer, vao = UINT(), UINT()
    gl("glGenFramebuffers", None, [INT, ct.POINTER(UINT)])(1, ct.byref(framebuffer))
    gl("glBindFramebuffer", None, [UINT, UINT])(0x8D40, framebuffer)
    gl("glGenVertexArrays", None, [INT, ct.POINTER(UINT)])(1, ct.byref(vao))
    gl("glBindVertexArray", None, [UINT])(vao)
    textures = (UINT * len(buffers))()
    gl("glGenTextures", None, [INT, PTR])(len(buffers), textures)
    attachments = (UINT * len(buffers))(*(0x8CE0 + i for i in range(len(buffers))))
    for i, texture in enumerate(textures):
        gl("glBindTexture", None, [UINT, UINT])(0x0DE1, texture)
        gl("glTexImage2D", None, [UINT, INT, INT, INT, INT, INT, UINT, UINT, PTR])(
            0x0DE1, 0, 0x8814, 1, 1, 0, 0x1908, 0x1406, None
        )
        gl("glFramebufferTexture2D", None, [UINT, UINT, UINT, UINT, INT])(0x8D40, attachments[i], 0x0DE1, texture, 0)
    gl("glDrawBuffers", None, [INT, PTR])(len(buffers), attachments)
    assert gl("glCheckFramebufferStatus", UINT, [UINT])(0x8D40) == 0x8CD5
    gl("glViewport", None, [INT, INT, INT, INT])(0, 0, 1, 1)
    modern = "complementary_modern:" in profile
    bsl = profile.startswith("bsl:")
    for normal in [(0, 0, 1), (0, 0, -1), (1, 0, 0)]:
        gl("glUniform3f", None, [INT, ct.c_float, ct.c_float, ct.c_float])(location(handle, b"TestNormal"), *normal)
        gl("glDrawArrays", None, [UINT, INT, INT])(4, 0, 3)
        for i, target in enumerate(buffers):
            pixel = (ct.c_float * 4)()
            gl("glReadBuffer", None, [UINT])(attachments[i])
            gl("glReadPixels", None, [INT, INT, INT, INT, UINT, UINT, PTR])(0, 0, 1, 1, 0x1908, 0x1406, pixel)
            assert all(math.isfinite(x) for x in pixel), (path, target, list(pixel))
            expected = None
            if target == 0:
                exponent = 1 if modern else 1.1 if "sqrt-linear" in profile else 2.2
                expected = [x**exponent for x in [.5, .25, .75]] + [.4]
            elif target == 13:
                expected = [.25, 1, 1, 1]
            elif modern and target == 4:
                expected = [-normal[1], normal[0], normal[2], 1]
            elif modern and target == 6:
                expected = [0, 254 / 255, 0, 1]
            elif target == 6:
                x, y = pixel[0] * 4 - 2, pixel[1] * 4 - 2
                assert abs(1 - (x*x+y*y)/2 - normal[2]) < .0001, (path, target, list(pixel))
            elif bsl and target == 1:
                expected = [(1 + (x**2.2-1)*math.sqrt(.4))*(1-.4**64) for x in [.6, .4, .2]] + [1]
            elif target == 3:
                if modern:
                    expected = [1 - (.666*(1-.4) + x*(1-.4**4)*.4) for x in [.5, .25, .75]] + [1] if "mix(vec3(0.666)" in source else [0, 0, 0, 1]
                else:
                    mask = 1 if ":glowing:" in profile else 0 if "alpha < 0.999" in source else .25
                    expected = [0, 0, mask, 1]
            elif bsl and target in (7, 8):
                expected = [0, 0, 0, 1]
            if expected is not None:
                assert all(abs(a-b) < .0001 for a,b in zip(pixel, expected)), (path, target, list(pixel), expected)
    gl("glBindFramebuffer", None, [UINT, UINT])(0x8D40, 0)
    gl("glDeleteFramebuffers", None, [INT, PTR])(1, ct.byref(framebuffer))
    gl("glDeleteVertexArrays", None, [INT, PTR])(1, ct.byref(vao))
    gl("glDeleteTextures", None, [INT, PTR])(len(buffers), textures)
    delete_program(handle)


checked = 0
for root in sys.argv[1:]:
    for metadata in sorted(pathlib.Path(root).glob("*.meta")):
        label = str(metadata)[:-5]
        profile, targets = metadata.read_text().splitlines()
        buffers = list(map(int, targets.split(",")))
        # Iris upgrades legacy GLSL versions before transforming them; newer pack options use texelFetch/%.
        original_vertex = re.sub(r"#version[^\n]*", "#version 330 compatibility",
                                 pathlib.Path(label + ".source.vsh").read_text())
        original_fragment = re.sub(r"#version[^\n]*", "#version 330 compatibility",
                                   pathlib.Path(label + ".source.fsh").read_text())
        original = program(original_vertex, original_fragment, label + " source")
        for render_pass in ("main", "outline"):
            fragment = pathlib.Path(label + "." + render_pass + ".glsl").read_text()
            vertex = pathlib.Path("common/src/main/resources/assets/mmdskin/shader/toon_" + render_pass + "_body.vert.glsl").read_text()
            generated = program(vertex, fragment, label + " " + render_pass)
            if render_pass == "main" and "complementary" in profile:
                # Mirrors the production bridge's requirement: every active pack uniform exists in the original.
                for name in re.findall(r"\buniform\s+\w+\s+(\w+)", fragment):
                    if name.startswith("Toon") or name in {
                        "Sampler0", "LightIntensity", "ToonLevels", "RimPower", "RimIntensity", "ShadowColor",
                        "SpecularPower", "SpecularIntensity", "AlphaCutoff", "GlobalAlpha", "UsePackLighting",
                    }:
                        continue
                    if location(generated, name.encode()) >= 0:
                        assert location(original, name.replace("ToonPackTexture", "texture").encode()) >= 0, (label, "missing uniform", name)
            delete_program(generated)
        read_outputs(pathlib.Path(label + ".writer.glsl"), profile, buffers)
        delete_program(original)
        assert gl("glGetError", UINT, [])() == 0, label
        checked += 1
        if checked % 24 == 0:
            print("Validated", checked, "programs", flush=True)
print("PASS:", checked, "programs; production main/outline links, pack uniforms, G-buffer readback", flush=True)
# Release the native context and surface.
egl("eglMakeCurrent", UINT, [PTR, PTR, PTR, PTR])(display, None, None, None)
egl("eglDestroySurface", UINT, [PTR, PTR])(display, surface)
egl("eglDestroyContext", UINT, [PTR, PTR])(display, context)
egl("eglTerminate", UINT, [PTR])(display)
