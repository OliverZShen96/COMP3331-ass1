import java.net.SocketTimeoutException;

public class MyThread extends Thread {
	public final int TIMEOUT_VALUE = 10000;
	
	@Override
	public void run() {
		int counter = 0;
		while (true) {
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			counter += 1;
			if (counter >= TIMEOUT_VALUE) {
				try {
					throw new SocketTimeoutException();
				} catch (SocketTimeoutException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}
	
	public static void main(String[] args) {
		
		
		
		
		Thread timer = new Thread(new Runnable() {
			@Override
			public void run() {
				int counter = 0;
				while (true) {
					try {
						Thread.sleep(10);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					counter += 1;
					if (counter >= 1000) {
					}
				}
			}
		});
		timer.start();
	}
}