#!/usr/bin/env python3
"""Surfaceless OpenGL regression of production GLSL (Mesa works; no Python packages).

Uses synthetic lighting contracts, NOT a Minecraft/PMX or real shaderpack acceptance test.
Run from the repo root after exporting ToonRotationFixtures with JDK 21; see README.
"""
import argparse
import ctypes as C
import json
import math
import os
from pathlib import Path

os.environ.setdefault('EGL_PLATFORM', 'surfaceless')
os.environ.setdefault('MESA_SHADER_CACHE_DIR', '/tmp/mmd-toon-mesa-cache')
I, U, F, P = C.c_int, C.c_uint, C.c_float, C.c_void_p


def function(lib, name, result, *args):
    f = getattr(lib, name)
    f.restype, f.argtypes = result, list(args)
    return f


class Context:
    def __init__(self):
        self.egl = C.CDLL('libEGL.so.1')
        self.gl = C.CDLL('libGL.so.1')
        self.display = function(self.egl, 'eglGetDisplay', P, P)(None)
        assert self.call('eglInitialize', I, P, P, P)(self.display, None, None)
        assert self.call('eglBindAPI', I, U)(0x30A2)
        config, count = P(), I()
        attrs = (I * 13)(0x3033, 1, 0x3040, 8, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
        assert self.call('eglChooseConfig', I, P, P, P, I, P)(self.display, attrs, C.byref(config), 1, C.byref(count))
        assert count.value
        self.surface = self.call('eglCreatePbufferSurface', P, P, P, P)(
            self.display, config, (I * 5)(0x3057, 256, 0x3056, 32, 0x3038))
        self.context = self.call('eglCreateContext', P, P, P, P, P)(
            self.display, config, None, (I * 7)(0x3098, 3, 0x30FB, 3, 0x30FD, 1, 0x3038))
        assert self.surface and self.context
        assert self.call('eglMakeCurrent', I, P, P, P, P)(self.display, self.surface, self.surface, self.context)

    def call(self, name, result, *args):
        return function(self.egl if name.startswith('egl') else self.gl, name, result, *args)

    def close(self):
        self.call('eglMakeCurrent', I, P, P, P, P)(self.display, None, None, None)
        self.call('eglDestroyContext', I, P, P)(self.display, self.context)
        self.call('eglDestroySurface', I, P, P)(self.display, self.surface)
        self.call('eglTerminate', I, P)(self.display)


def compile_program(gl, vertex, fragment, label):
    shaders = []
    program = gl('glCreateProgram', U)()
    try:
        for kind, source in [(0x8B31, vertex), (0x8B30, fragment)]:
            shader = gl('glCreateShader', U, U)(kind)
            shaders.append(shader)
            src = C.c_char_p(source.encode())
            gl('glShaderSource', None, U, I, P, P)(shader, 1, C.byref(src), None)
            gl('glCompileShader', None, U)(shader)
            status = I()
            gl('glGetShaderiv', None, U, U, P)(shader, 0x8B81, C.byref(status))
            if not status.value:
                log = C.create_string_buffer(32768)
                gl('glGetShaderInfoLog', None, U, I, P, P)(shader, len(log), None, log)
                raise AssertionError(f'{label}: {log.value.decode()}')
            gl('glAttachShader', None, U, U)(program, shader)
        gl('glLinkProgram', None, U)(program)
        status = I()
        gl('glGetProgramiv', None, U, U, P)(program, 0x8B82, C.byref(status))
        assert status.value, f'link: {label}'
        return program
    except BaseException:
        gl('glDeleteProgram', None, U)(program)
        raise
    finally:
        for shader in shaders:
            gl('glDeleteShader', None, U)(shader)


def check_vertices(gl, observe):
    """Execute both real vertex shaders; compensate model yaw as the Java renderers do."""
    buffer = U()
    gl('glGenBuffers', None, I, P)(1, C.byref(buffer))
    gl('glBindBuffer', None, U, U)(0x8892, buffer)
    positions = (F * 9)(-1, -1, -.5, 3, -1, -.5, -1, 3, -.5)
    gl('glBufferData', None, U, C.c_ssize_t, P, U)(0x8892, C.sizeof(positions), positions, 0x88E4)
    gl('glEnableVertexAttribArray', None, U)(0)
    gl('glVertexAttribPointer', None, U, I, U, U, I, P)(0, 3, 0x1406, 0, 0, None)
    gl('glVertexAttrib3f', None, U, F, F, F)(1, .6, .8, 0)
    worst, worst_case = 0, None
    try:
        for name in ('toon_main_body.vert.glsl', 'toon_outline_body.vert.glsl'):
            source = Path('common/src/main/resources/assets/mmdskin/shader', name).read_text()
            program = compile_program(gl, source, '''#version 330 core
in vec3 viewNormal, viewLightDir;
out vec4 pixel;
void main() { pixel = vec4(normalize(viewNormal), dot(normalize(viewNormal), normalize(viewLightDir))); }
''', name)
            try:
                gl('glUseProgram', None, U)(program)
                for scale in ((1, 1, 1), (2, .5, 1.5)):
                    sx, sy, sz = scale
                    for yaw in range(0, 361, 15):
                        a = math.radians(yaw)
                        c, s = math.cos(a), math.sin(a)
                        mv = (F * 16)(c*sx,0,-s*sx,0, 0,sy,0,0, s*sz,0,c*sz,0, 0,0,0,1)
                        inv = (F * 16)(c/sx,0,s/sz,0, 0,1/sy,0,0, -s/sx,0,c/sz,0, 0,0,0,1)
                        for uniform, value in (('ModelViewMat', mv), ('ProjMat', inv)):
                            loc = gl('glGetUniformLocation', I, U, C.c_char_p)(program, uniform.encode())
                            gl('glUniformMatrix4fv', None, I, I, U, P)(loc, 1, 0, value)
                        loc = gl('glGetUniformLocation', I, U, C.c_char_p)(program, b'LightDir')
                        gl('glUniform3f', None, I, F, F, F)(loc, c*.8/sx, .6/sy, s*.8/sz)
                        gl('glDrawArrays', None, U, I, I)(4, 0, 3)
                        pixel = (F * 4)()
                        gl('glReadPixels', None, I, I, I, I, U, U, P)(128, 16, 1, 1, 0x1908, 0x1406, pixel)
                        normal = [c*.6/sx, .8/sy, -s*.6/sx]
                        length = math.sqrt(sum(v*v for v in normal))
                        normal = [v/length for v in normal]
                        expected = normal + [normal[0]*.8 + normal[1]*.6]
                        error = max(abs(x-y) for x,y in zip(pixel, expected))
                        if error > worst:
                            worst, worst_case = error, (name, scale, yaw, list(pixel), expected)
                        if not observe:
                            assert error < 2e-5, (name, scale, yaw, list(pixel), expected)
            finally:
                gl('glDeleteProgram', None, U)(program)
    finally:
        gl('glDisableVertexAttribArray', None, U)(0)
        gl('glDeleteBuffers', None, I, P)(1, C.byref(buffer))
    print('vertex normal/light maximum error:', worst, worst_case)
    return worst


def run(args):
    ctx = Context()
    gl = ctx.call
    programs = []
    try:
        renderer = gl('glGetString', C.c_char_p, U)(0x1F01).decode()
        version = gl('glGetString', C.c_char_p, U)(0x1F02).decode()
        print(version, '/', renderer)
        vao = U()
        gl('glGenVertexArrays', None, I, P)(1, C.byref(vao))
        gl('glBindVertexArray', None, U)(vao)
        # Float attachment catches NaN/Inf and avoids gamma/clamping masking failures.
        tex, fbo = U(), U()
        gl('glGenTextures', None, I, P)(1, C.byref(tex))
        gl('glBindTexture', None, U, U)(0x0DE1, tex)
        gl('glTexImage2D', None, U, I, I, I, I, I, U, U, P)(0x0DE1, 0, 0x8814, 256, 32, 0, 0x1908, 0x1406, None)
        gl('glGenFramebuffers', None, I, P)(1, C.byref(fbo))
        gl('glBindFramebuffer', None, U, U)(0x8D40, fbo)
        gl('glFramebufferTexture2D', None, U, U, U, U, I)(0x8D40, 0x8CE0, 0x0DE1, tex, 0)
        assert gl('glCheckFramebufferStatus', U, U)(0x8D40) == 0x8CD5
        gl('glViewport', None, I, I, I, I)(0, 0, 256, 32)
        vertex_error = check_vertices(gl, args.observe)
        vertex = '''#version 330 core
void main() { vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0); }
'''
        surface = Path('common/src/main/resources/assets/mmdskin/shader/toon_surface_lighting.glsl').read_text()
        results = []
        fixtures = sorted(args.fixtures.glob('*.glsl'))
        assert {p.stem for p in fixtures} >= {'bsl', 'complementary4', 'complementary5', 'vanilla'}, 'Missing fixtures'
        for fixture in fixtures:
            fragment = '''#version 330 core
#define TOON_LIGHTING_DEBUG 3
vec2 texCoord0 = vec2(0.0);
vec3 viewLightDir = normalize(vec3(0.8, 0.6, 0.0));
uniform float TestYaw, TestVisibility, TestEnergy;
uniform int TestMode;
uniform bool TestOverrideNormal;
uniform vec3 TestNormal;
out vec4 pixel;
''' + surface.replace('/* TOON_LIGHTING */', fixture.read_text()) + '''
void main() {
    float a = (floor(gl_FragCoord.x / 4.0) + 0.5) / 64.0 * 6.2831853;
    float y = (floor(gl_FragCoord.y / 4.0) + 0.5) / 8.0 * 2.0 - 1.0;
    vec3 n = vec3(cos(a) * sqrt(1.0-y*y), y, sin(a) * sqrt(1.0-y*y));
    if (TestOverrideNormal) n = normalize(TestNormal);
    n = mat3(cos(TestYaw),0,-sin(TestYaw), 0,1,0, sin(TestYaw),0,cos(TestYaw)) * n;
    vec3 color = applyPackLighting(vec3(0.6), n, vec3(0.0,0.0,-2.0));
    pixel = vec4(color, 0.4);
    if (TestMode == 1) pixel = vec4(clamp(dot(n, viewLightDir),0.0,1.0),
        toonSunlightStrength, toonSunVisibility, 1.0);
    if (TestMode == 2) pixel = vec4(toonDebugBand, toonDebugDirect, toonSunVisibility, toonSunlightStrength);
}
'''
            program = compile_program(gl, vertex, fragment, fixture.name)
            programs.append(program)
            gl('glUseProgram', None, U)(program)
            for name in ('gbufferModelView', 'gbufferModelViewInverse'):
                loc = gl('glGetUniformLocation', I, U, C.c_char_p)(program, name.encode())
                identity = (F * 16)(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1)
                gl('glUniformMatrix4fv', None, I, I, U, P)(loc, 1, 0, identity)

            def uniform(name, *values, integer=False):
                loc = gl('glGetUniformLocation', I, U, C.c_char_p)(program, name.encode())
                suffix = f'{len(values)}' + ('i' if integer else 'f')
                gl('glUniform' + suffix, None, I, *([I if integer else F] * len(values)))(loc, *values)

            uniform('UsePackLighting', int(fixture.stem != 'vanilla'), integer=True)
            uniform('ShadowColor', .45, .55, .65)
            uniform('SpecularIntensity', 0)
            uniform('RimIntensity', 0)
            uniform('TestEnergy', 1)

            def sample(flatness, yaw=0, visibility=1, mode=0):
                uniform('ToonShaderFlatness', flatness)
                uniform('TestYaw', math.radians(yaw))
                uniform('TestVisibility', visibility)
                uniform('TestMode', mode, integer=True)
                gl('glDrawArrays', None, U, I, I)(4, 0, 3)
                data = (F * (256 * 32 * 4))()
                gl('glReadPixels', None, I, I, I, I, U, U, P)(0, 0, 256, 32, 0x1908, 0x1406, data)
                assert all(math.isfinite(v) for v in data), f'non-finite: {fixture.name}'
                return [tuple(data[(y * 256 + x) * 4:(y * 256 + x) * 4 + 4])
                        for y in range(2, 32, 4) for x in range(2, 256, 4)]

            for levels in range(2, 6):
                uniform('ToonLevels', levels, integer=True)
                uniform('ToonTransitionSoftness', 0)
                uniform('ToonLightmap', 8 / 256, 248 / 256)
                for flatness in (0, .7, 1):
                    spreads, counts, plateau_fractions = [], [], []
                    first = None
                    for yaw in range(0, 361, 15):
                        pixels = sample(flatness, yaw)
                        spread = max(p[0] for p in pixels) - min(p[0] for p in pixels)
                        # Each normal occupies a 4x4 region: stable derivatives at the sampled centers.
                        colors = {tuple(round(v, 4) for v in p[:3]) for p in pixels}
                        spreads.append(spread)
                        counts.append(len(colors))
                        if yaw == 0:
                            first = pixels
                        if yaw == 360:
                            seam_error = max(abs(x-y) for p,q in zip(first,pixels) for x,y in zip(p,q))
                            # FP32 sin(2*pi) can move an antialiased boundary by a few ULPs.
                            assert seam_error < 1e-4, (fixture.name, levels, flatness, seam_error)
                        assert all(abs(p[3] - .4) < 1e-6 for p in pixels)
                        if not args.observe:
                            assert spread > .1, (fixture.name, levels, flatness, yaw, spread)
                            assert levels <= len(colors) <= 4 * levels, (fixture.name, levels, flatness, yaw, len(colors))
                        diagnostic = sample(flatness, yaw, mode=2)
                        plateau_bands = [round(p[0] * (levels-1)) for p in diagnostic
                                         if abs(p[0]*(levels-1) - round(p[0]*(levels-1))) < 1e-4]
                        plateau_fractions.append(len(plateau_bands) / len(diagnostic))
                        if not args.observe:
                            assert set(plateau_bands) == set(range(levels)), (fixture.name, yaw, plateau_bands)
                            assert plateau_fractions[-1] > .95, (fixture.name, yaw, plateau_fractions[-1])
                    results.append(dict(path=fixture.stem, levels=levels, flatness=flatness,
                                        minSpread=min(spreads), minBands=min(counts), maxBands=max(counts),
                                        minPlateauFraction=min(plateau_fractions),
                                        sunlightStrengthRange=[min(p[3] for p in diagnostic), max(p[3] for p in diagnostic)],
                                        directRampRange=[min(p[1] for p in diagnostic), max(p[1] for p in diagnostic)]))
                # World visibility modulates illumination, not the band selector.
                lit, shadow = sample(1), sample(1, visibility=0)
                if fixture.stem != 'vanilla':
                    assert sum(p[0] for p in shadow) < sum(p[0] for p in lit)
                # Low sky prevents daytime fill, including block-lit interiors.
                uniform('ToonLightmap', 248 / 256, 8 / 256)
                a, b = sample(0), sample(1)
                assert max(abs(x-y) for p,q in zip(a,b) for x,y in zip(p,q)) < 1e-5
                uniform('ToonLightmap', 8 / 256, 248 / 256)
                uniform('TestNight', 1, integer=True)
                a, b = sample(0), sample(1)
                assert max(abs(x-y) for p,q in zip(a,b) for x,y in zip(p,q)) < 1e-5
                uniform('TestNight', 0, integer=True)
                # Synthetic weather/energy input must still dim every surface band.
                day = sample(1)
                uniform('TestEnergy', .35)
                rain = sample(1)
                assert all(r[0] < d[0] for r,d in zip(rain,day))
                uniform('TestEnergy', 1)
                # Transitions must remain finite at/around the terminator, including maximum softness.
                uniform('TestOverrideNormal', 1, integer=True)
                for softness in (.5, 1, 2):
                    uniform('ToonTransitionSoftness', softness)
                    for ndl in (-1e-7, 0, 1e-7):
                        uniform('TestNormal', -.6 + ndl*.8, .8 + ndl*.6, 0)
                        for flatness in (0, .7, 1):
                            sample(flatness)
                uniform('TestOverrideNormal', 0, integer=True)
            print('PASS' if not args.observe else 'OBSERVE', fixture.stem, results[-1])
        assert gl('glGetError', U)() == 0
        production = args.fixtures.parent / 'production'
        compiled = 0
        if production.is_dir():
            for fragment in sorted(production.glob('*.frag')):
                pass_name = fragment.name.split('.')[-2]
                vertex = (production / f'{pass_name}.vert').read_text()
                for mode in (range(10) if pass_name == 'main' else (0,)):
                    source = fragment.read_text()
                    if mode:
                        source = source.replace('#version 330 core', f'#version 330 core\n#define TOON_LIGHTING_DEBUG {mode}')
                    program = compile_program(gl, vertex, source, f'{fragment.name}/debug={mode}')
                    gl('glDeleteProgram', None, U)(program)
                    compiled += 1
            print('PASS complete production body/outline/debug compilation:', compiled)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(dict(renderer=renderer, version=version, vertexMaxError=vertex_error,
                                               productionProgramsCompiled=compiled, results=results), indent=2) + '\n')
    finally:
        for program in programs:
            gl('glDeleteProgram', None, U)(program)
        ctx.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixtures', type=Path, default=Path('build/toon-rotation/fixtures'))
    parser.add_argument('--output', type=Path, default=Path('build/toon-rotation/result.json'))
    parser.add_argument('--observe', action='store_true', help='measure old behavior without asserting band retention')
    run(parser.parse_args())
