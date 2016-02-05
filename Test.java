public class Test {
	public static void main(String[] args) {
		for (String s : args) {
			if (s.equals("-f")) {
				System.out.println(s);
			}
			
		}
	}
}