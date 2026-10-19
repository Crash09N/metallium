#version 120
// Metallium history test pack (Apache-2.0): terrain writes its albedo to colortex0 and to the mipmapped colortex4, as
// superDuperVanilla's gbuffers do (the pack's passes then run in the gbuffers pipeline, not the stage-2 one).
uniform sampler2D gtexture;
varying vec2 texcoord;
varying vec4 glcolor;

void main() {
	vec4 albedo = texture2D(gtexture, texcoord) * glcolor;
	/* DRAWBUFFERS:04 */
	gl_FragData[0] = albedo;
	gl_FragData[1] = albedo;
}
