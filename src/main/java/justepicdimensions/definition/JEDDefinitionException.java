package justepicdimensions.definition;

/** A configuration error that can be reported without applying a partial definition set. */
public final class JEDDefinitionException extends IllegalArgumentException {
    private final net.minecraft.network.chat.Component display;
    public JEDDefinitionException(String message) { super(message);display=justepicdimensions.JEDText.fixed(message); }
    public JEDDefinitionException(String message, Throwable cause) { super(message, cause);display=justepicdimensions.JEDText.fixed(message); }
    public JEDDefinitionException(String message,Throwable cause,net.minecraft.network.chat.Component display){super(message,cause);this.display=display;}
    public net.minecraft.network.chat.Component display(){return display;}
}
