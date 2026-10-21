#version 120
// Nativium gbuffers test pack: terrain writes its albedo to colortex0 and a marker (pure green) to colortex1.
// colortex1 is cleared to white every frame (Iris default), so the marker only exists where terrain was drawn.
uniform sampler2D gtexture;
varying vec2 texcoord;
varying vec4 glcolor;

void main() {
	vec4 albedo = texture2D(gtexture, texcoord) * glcolor;
	/* DRAWBUFFERS:01 */
	gl_FragData[0] = albedo;
	gl_FragData[1] = vec4(0.0, 1.0, 0.0, 1.0);
}
