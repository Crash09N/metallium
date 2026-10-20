#version 330 compatibility
// Metallium history test pack (MIT): checks in the top 10 % of the screen (green = pass, red = fail), in four
// columns:
//   1. the mip chain of colortex4 (read from its alt texture): level 4 left of centre is red, the 1x1 level purple
//   2. the same for colortex6 (read from its main texture)
//   3. colortex5 (colortex5Clear = false) holds what this pass wrote in the previous frame: frameCounter - 1
//   4. previous-frame uniforms: gbufferPreviousModelView/Projection and previousCameraPosition equal the current
//      values of the previous frame, which this pass stored in colortex5/colortex7 (the camera does not move)
// Everything else shows the scene.
/*
const int colortex4Format = R11F_G11F_B10F;
const int colortex6Format = RGBA16F;
const int colortex5Format = RGBA32F;
const int colortex7Format = RGBA32F;
*/
const bool colortex5Clear = false;
const bool colortex7Clear = false;
const bool colortex4MipmapEnabled = true;
const bool colortex6MipmapEnabled = true;

uniform sampler2D colortex0;
uniform sampler2D colortex4;
uniform sampler2D colortex5;
uniform sampler2D colortex6;
uniform sampler2D colortex7;
uniform int frameCounter;
uniform mat4 gbufferModelView;
uniform mat4 gbufferProjection;
uniform mat4 gbufferPreviousModelView;
uniform mat4 gbufferPreviousProjection;
uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;
in vec2 texcoord;

/* DRAWBUFFERS:057 */
layout(location = 0) out vec4 color;
layout(location = 1) out vec4 history;
layout(location = 2) out vec4 history2;

bool mipsOk(sampler2D tex) {
	vec3 level4 = textureLod(tex, vec2(0.25, 0.5), 4.0).rgb;
	vec3 smallest = textureLod(tex, vec2(0.25, 0.5), 100.0).rgb;
	return distance(level4, vec3(1.0, 0.0, 0.0)) < 0.1 && abs(smallest.r - 0.5) < 0.15 && abs(smallest.b - 0.5) < 0.15 && smallest.g < 0.1;
}

bool close(float a, float b) {
	return abs(a - b) <= 1e-3 * max(1.0, abs(a));
}

void main() {
	// Previous frame's values, stored at a fixed texel (written by every pixel, so the same everywhere).
	vec4 prev0 = texelFetch(colortex5, ivec2(0, 0), 0);
	vec4 prev1 = texelFetch(colortex7, ivec2(0, 0), 0);
	// Current values for the next frame.
	history = vec4(float(frameCounter), gbufferModelView[0][0], gbufferModelView[2][1], gbufferProjection[1][1]);
	history2 = vec4(cameraPosition, gbufferProjection[2][2]);

	vec3 result = texture(colortex0, texcoord).rgb;
	if (texcoord.y > 0.9) {
		bool ok;
		if (texcoord.x < 0.25) {
			ok = mipsOk(colortex4);
		} else if (texcoord.x < 0.5) {
			ok = mipsOk(colortex6);
		} else if (texcoord.x < 0.75) {
			ok = prev0.x == float(frameCounter - 1);
		} else {
			ok = close(prev0.y, gbufferPreviousModelView[0][0]) && close(prev0.z, gbufferPreviousModelView[2][1])
					&& close(prev0.w, gbufferPreviousProjection[1][1]) && close(prev1.w, gbufferPreviousProjection[2][2])
					&& all(lessThan(abs(prev1.xyz - previousCameraPosition), vec3(1e-3)))
					&& prev0.w != 0.0;
		}
		result = ok ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
	}
	color = vec4(result, 1.0);
}
