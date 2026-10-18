#version 120
// Metallium compute test pack (Apache-2.0): the right third of the top band shows colortex5, which composite2.fsh
// wrote through colorimg5 (image store from a fragment program); everything else passes colortex0 through.
uniform sampler2D colortex0;
uniform sampler2D colortex5;
varying vec2 texcoord;

void main() {
	vec3 result = texcoord.y > 0.9 && texcoord.x > 2.0 / 3.0 ? texture2D(colortex5, texcoord).rgb : texture2D(colortex0, texcoord).rgb;
	/* DRAWBUFFERS:0 */
	gl_FragData[0] = vec4(result, 1.0);
}
