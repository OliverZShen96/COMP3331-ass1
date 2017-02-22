
public class Timer extends Thread{
	public int counter;
	public boolean running;
	@Override
	public void run() {
		running = true;
		counter = 0;
		while (running) {
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			counter += 1;
		}
	}
	
	public int getTimeElapsed() {
		return counter;
	}
	
	public void kill(){
        running = false;
    }
}
