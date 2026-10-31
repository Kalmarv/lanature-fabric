package xyz.ivyhq.lanature.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MolangTest {
	private static Scope scope() {
		return new Scope(new Variables(), new Variables(), new Variables(), null, new Random(1));
	}

	private static double eval(String src, Scope s) {
		return Molang.eval(Molang.parseStrict(src), s);
	}

	@Test
	void arithmeticAndPrecedence() {
		Scope s = scope();
		assertEquals(7, eval("1 + 2 * 3", s));
		assertEquals(9, eval("(1 + 2) * 3", s));
		assertEquals(-1, eval("-1", s));
		assertEquals(1, eval("1 < 2 && 2 <= 2", s));
		assertEquals(0, eval("!1", s));
		assertEquals(0.5, eval("1.0f / 2", s));
		assertEquals(1, eval("1 == 1.0", s));
	}

	@Test
	void conditionalForms() {
		Scope s = scope();
		assertEquals(2, eval("0 ? 1 : 2", s));
		assertEquals(0, eval("0 ? 1", s));
		assertEquals(3, eval("1 ? 0 ? 2 : 3 : 4", s));
	}

	@Test
	void variablesStructsAndCoalesce() {
		Scope s = scope();
		Molang.run(Molang.parseStrict("v.a = 3; v.color.r = 0.5; t.c = v.color;"), s);
		assertEquals(3, eval("v.a", s));
		assertEquals(0.5, eval("t.c.r", s));
		assertEquals(4, eval("v.missing ?? 4", s));
		assertEquals(3, eval("v.a ?? 4", s));
		assertEquals(0, eval("v.missing", s));
	}

	@Test
	void complexExpressionsReturn() {
		Scope s = scope();
		assertEquals(5, eval("v.x = 2; return v.x + 3;", s));
		assertEquals(0, eval("v.x = 2;", s));
		assertEquals(6, eval("v.i = 0; loop(10, { v.i = v.i + 1; (v.i >= 6) ? break; }); return v.i;", s));
	}

	@Test
	void scriptArraysJoin() {
		Scope s = scope();
		Node n = Molang.compileScript(List.of("v.base = 1;", "(v.base) ? {", "v.y = 7;", "};"));
		Molang.run(n, s);
		assertEquals(7, eval("v.y", s));
	}

	@Test
	void stringsCompare() {
		Scope s = scope();
		Molang.run(Molang.parseStrict("v.n = 'abc';"), s);
		assertEquals(1, eval("v.n == 'abc'", s));
		assertEquals(1, eval("v.n != 'abd'", s));
	}

	@Test
	void mathUsesDegrees() {
		Scope s = scope();
		assertEquals(1, eval("math.sin(90)", s), 1e-9);
		assertEquals(-1, eval("math.cos(180)", s), 1e-9);
		assertEquals(Math.PI, eval("math.pi", s), 1e-12);
		double r = eval("math.random(2, 3)", s);
		assertTrue(r >= 2 && r < 3);
		double ri = eval("math.random_integer(1, 3)", s);
		assertTrue(ri == 1 || ri == 2 || ri == 3);
	}

	@Test
	void builtinsReadSlots() {
		Scope s = scope();
		s.set(Scope.Builtin.PARTICLE_AGE, 0.25);
		assertEquals(0.5, eval("v.particle_age * 2", s));
		assertEquals(0.25, eval("variable.particle_age", s));
	}

	@Test
	void queriesBindLate() {
		Node n = Molang.parseStrict("q.lanature_test_query(2) + 1");
		Functions.registerNumericQuery("lanature_test_query", (s, a) -> a[0].eval(s) * 10);
		assertEquals(21, Molang.eval(n, scope()));
	}
}
