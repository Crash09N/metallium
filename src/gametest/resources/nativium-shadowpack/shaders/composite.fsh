#version 120
// Nativium shadow test pack (MIT, written for Nativium's game tests): every terrain/entity pixel is projected
// into the shadow map; pixels in shadow become magenta (the game test counts them), lit pixels keep their colour.
// shadowtex0 is a sampler2DShadow with hardware filtering, i.e. a depth-compare sampler on Metal.
const int shadowMapResolution = 2048;
const float shadowDistance = 96.0;
const bool shadowHardwareFiltering = true;

uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform sampler2DShadow shadowtex0;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
varying vec2 texcoord;

void main() {
	vec3 color = texture2D(colortex0, texcoord).rgb;
	float depth = texture2D(depthtex0, texcoord).r;
	vec3 result = color;
	if (depth < 1.0) {
		vec4 view = gbufferProjectionInverse * vec4(vec3(texcoord, depth) * 2.0 - 1.0, 1.0);
		view /= view.w;
		vec4 world = gbufferModelViewInverse * view;
		vec4 shadowClip = shadowProjection * (shadowModelView * world);
		vec3 shadowPos = shadowClip.xyz / shadowClip.w * 0.5 + 0.5;
		if (all(greaterThan(shadowPos.xy, vec2(0.0))) && all(lessThan(shadowPos.xy, vec2(1.0)))) {
			float lit = shadow2D(shadowtex0, vec3(shadowPos.xy, shadowPos.z - 0.002)).r;
			if (lit < 0.5) {
				result = vec3(1.0, 0.0, 1.0);
			}
		}
	}
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(result, 1.0);
}
