package nexus.core.exec;

/**
 * The code of a node. One instance is created per execution, so implementations may keep state in
 * fields for the duration of a run. It runs on its own virtual thread: blocking (on a file, a
 * socket, another node's stream) is fine; long loops should call {@link NodeContext#checkCancelled()}.
 */
@FunctionalInterface
public interface NodeBehavior {
    void execute(NodeContext ctx) throws Exception;
}
