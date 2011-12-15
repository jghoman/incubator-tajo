package nta.catalog;

import static org.junit.Assert.*;

import nta.catalog.proto.TableProtos.DataType;
import nta.catalog.proto.TableProtos.SchemaProto;

import org.junit.Before;
import org.junit.Test;

public class TestSchema {
	
	Schema schema;
	int cid1;
	int cid2;
	int cid3;

	@Before
	public void setUp() throws Exception {
		schema = new Schema();
		cid1 = schema.addColumn("name", DataType.STRING);
		cid2 = schema.addColumn("age", DataType.INT);
		cid3 = schema.addColumn("addr", DataType.STRING);
	}

	@Test
	public final void testSchemaSchema() {
		Schema schema2 = new Schema(schema);
		
		assertEquals(schema, schema2);
	}

	@Test
	public final void testSchemaSchemaProto() {
		Schema schema2 = new Schema(schema.getProto());
		
		assertEquals(schema, schema2);
	}

	@Test
	public final void testGetColumnInt() {
		assertTrue(0 == schema.getColumn(0).getId());
		assertTrue(1 == schema.getColumn(1).getId());
		assertTrue(2 == schema.getColumn(2).getId());
	}

	@Test
	public final void testGetColumnString() {
		assertTrue(0 == schema.getColumn("name").getId());
		assertTrue(1 == schema.getColumn("age").getId());
		assertTrue(2 == schema.getColumn("addr").getId());
	}

	@Test
	public final void testAddField() {
		Schema schema = new Schema();
		assertFalse(schema.contains("studentId"));
		schema.addColumn("studentId", DataType.INT);
		assertTrue(schema.contains("studentId"));
	}

	@Test
	public final void testEqualsObject() {
		Schema schema2 = new Schema();
		cid1 = schema2.addColumn("name", DataType.STRING);
		cid2 = schema2.addColumn("age", DataType.INT);
		cid3 = schema2.addColumn("addr", DataType.STRING);
		
		assertEquals(schema, schema2);
	}

	@Test
	public final void testGetProto() {
		SchemaProto proto = schema.getProto();
		
		assertEquals("name", proto.getFields(0).getColumnName());
		assertEquals("age", proto.getFields(1).getColumnName());
		assertEquals("addr", proto.getFields(2).getColumnName());
	}

}