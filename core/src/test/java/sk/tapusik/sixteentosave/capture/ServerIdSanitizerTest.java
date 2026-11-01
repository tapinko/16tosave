package sk.tapusik.sixteentosave.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ServerIdSanitizerTest {
	@Test
	void defaultPortIsAddedSoBothSpellingsMatch() {
		assertEquals("play.example.com_25565", ServerIdSanitizer.fromAddress("play.example.com"));
		assertEquals("play.example.com_25565", ServerIdSanitizer.fromAddress(" Play.Example.COM:25565 "));
		assertEquals("play.example.com_25565", ServerIdSanitizer.fromAddress("play.example.com."));
	}

	@Test
	void customPortIsKept() {
		assertEquals("10.0.0.5_25570", ServerIdSanitizer.fromAddress("10.0.0.5:25570"));
	}

	@Test
	void badPortFallsBackToDefault() {
		assertEquals("host_25565", ServerIdSanitizer.fromAddress("host:abc"));
		assertEquals("host_25565", ServerIdSanitizer.fromAddress("host:70000"));
	}

	@Test
	void ipv6() {
		assertEquals("__1_25570", ServerIdSanitizer.fromAddress("[::1]:25570"));
		assertEquals("__1_25565", ServerIdSanitizer.fromAddress("::1"));
	}

	@Test
	void emptyAndRealms() {
		assertEquals("unknown", ServerIdSanitizer.fromAddress(""));
		assertEquals("unknown", ServerIdSanitizer.fromAddress(null));
		assertEquals("realms_my_realm", ServerIdSanitizer.realm("My Realm"));
		assertEquals("realms_unknown", ServerIdSanitizer.realm(" "));
	}
}
