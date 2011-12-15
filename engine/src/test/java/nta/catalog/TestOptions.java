package nta.catalog;

import static org.junit.Assert.*;

import nta.catalog.proto.TableProtos.OptionsProto;

import org.junit.Before;
import org.junit.Test;

public class TestOptions {
	@Test
	public final void testPutAndGet() {
		Options opts = new Options();
		opts.put("name", "abc");
		opts.put("delimiter", ",");
		
		assertEquals(",", opts.get("delimiter"));
		assertEquals("abc", opts.get("name"));
	}

	@Test
	public final void testGetProto() {		
		Options opts = new Options();
		opts.put("name", "abc");
		opts.put("delimiter", ",");
		
		OptionsProto proto = opts.getProto();
		Options opts2 = new Options(proto);
		
		assertEquals(opts, opts2);
	}
	
	@Test
	public final void testDelete() {
		Options opts = new Options();
		opts.put("name", "abc");
		opts.put("delimiter", ",");
		
		assertEquals("abc", opts.get("name"));
		assertEquals("abc", opts.delete("name"));
		assertNull(opts.get("name"));
		
		Options opts2 = new Options(opts.getProto());
		assertNull(opts.get("name"));
	}
}