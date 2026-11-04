import com.sun.tools.attach.VirtualMachine;
import java.io.IOException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/** Requests graceful shutdown through Spring's local admin MBean, without a remote management port. */
public final class PilotShutdown {
    /** Attaches only to the explicit child PID owned by the pilot and closes its application context. */
    public static void main(String[] args) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        String address;
        try {
            address = vm.startLocalManagementAgent();
        } finally {
            vm.detach();
        }
        try (var connector = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            connector.getMBeanServerConnection().invoke(
                    new ObjectName("org.springframework.boot:type=Admin,name=SpringApplication"),
                    "shutdown", new Object[0], new String[0]);
        } catch (IOException closedDuringShutdown) {
            // The application may close its local connector while processing shutdown.
            // The harness independently waits for the exact child process to exit.
        }
    }
}
