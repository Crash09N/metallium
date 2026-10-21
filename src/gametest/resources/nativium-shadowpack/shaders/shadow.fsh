#version 120
// Nativium shadow test pack (MIT, written for Nativium's game tests): writes shadow depth (and a white
// shadowcolor0); cutout pixels are discarded so leaves cast leaf-shaped shadows.
const int shadowMapResolution = 2048;
const float shadowDistance = 96.0;
const bool shadowHardwareFiltering = true;

uniform sampler2D gtexture;
varying vec2 texcoord;

void main() {
	if (texture2D(gtexture, texcoord).a < 0.1) discard;
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(1.0);
}
