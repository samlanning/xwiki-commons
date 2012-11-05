/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.velocity.internal;

import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.inject.Inject;

import org.apache.velocity.context.Context;
import org.apache.velocity.context.InternalContextAdapterImpl;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.RuntimeServices;
import org.apache.velocity.runtime.directive.Scope;
import org.apache.velocity.runtime.directive.StopCommand;
import org.apache.velocity.runtime.log.LogChute;
import org.apache.velocity.runtime.parser.node.SimpleNode;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.velocity.VelocityConfiguration;
import org.xwiki.velocity.VelocityContextFactory;
import org.xwiki.velocity.VelocityEngine;
import org.xwiki.velocity.XWikiVelocityException;
import org.xwiki.velocity.internal.log.SLF4JLogChute;

/**
 * Default implementation of the Velocity service which initializes the Velocity system using configuration values
 * defined in the component's configuration. Note that the {@link #initialize} method has to be executed before any
 * other method can be called.
 * 
 * @version $Id$
 */
@Component
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class DefaultVelocityEngine implements VelocityEngine, LogChute
{
    /**
     * The name of the context variable used for the template-level scope.
     */
    private static final String TEMPLATE_SCOPE_NAME = "template";

    /**
     * Used to set it as a Velocity Application Attribute so that Velocity extensions done by XWiki can use it to lookup
     * other components.
     */
    @Inject
    private ComponentManager componentManager;

    /**
     * Velocity configuration to get the list of configured Velocity properties.
     */
    @Inject
    private VelocityConfiguration velocityConfiguration;

    /**
     * Used to create a new context whenever one isn't already provided to the
     * {@link #evaluate(Context, Writer, String, Reader)} method.
     */
    @Inject
    private VelocityContextFactory velocityContextFactory;

    /**
     * The logger to use for logging.
     */
    @Inject
    private Logger logger;

    /**
     * The Velocity engine we're wrapping.
     */
    private org.apache.velocity.app.VelocityEngine engine;

    /**
     * See the comment in {@link #init(org.apache.velocity.runtime.RuntimeServices)}.
     */
    private RuntimeServices rsvc;

    /** Counter for the number of active rendering processes using each namespace. */
    private final Map<String, Integer> namespaceUsageCount = new HashMap<String, Integer>();

    @Override
    public void initialize(Properties overridingProperties) throws XWikiVelocityException
    {
        org.apache.velocity.app.VelocityEngine velocityEngine = new org.apache.velocity.app.VelocityEngine();

        // Add the Component Manager to allow Velocity extensions to lookup components.
        velocityEngine.setApplicationAttribute(ComponentManager.class.getName(), this.componentManager);
        velocityEngine.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM, new SLF4JLogChute());

        initializeProperties(velocityEngine, this.velocityConfiguration.getProperties(), overridingProperties);

        try {
            velocityEngine.init();
        } catch (Exception e) {
            throw new XWikiVelocityException("Cannot start the Velocity engine", e);
        }

        this.engine = velocityEngine;
    }

    /**
     * @param velocityEngine the Velocity engine against which to initialize Velocity properties
     * @param configurationProperties the Velocity properties coming from XWiki's configuration
     * @param overridingProperties the Velocity properties that override the properties coming from XWiki's
     *        configuration
     */
    private void initializeProperties(org.apache.velocity.app.VelocityEngine velocityEngine,
        Properties configurationProperties, Properties overridingProperties)
    {
        // Avoid "unable to find resource 'VM_global_library.vm' in any resource loader." if no
        // Velocimacro library is defined. This value is overriden below.
        velocityEngine.setProperty("velocimacro.library", "");

        velocityEngine.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM, this);

        // Configure Velocity by passing the properties defined in this component's configuration
        if (configurationProperties != null) {
            for (Enumeration< ? > e = configurationProperties.propertyNames(); e.hasMoreElements();) {
                String key = e.nextElement().toString();
                // Only set a property if it's not overridden by one of the passed properties
                if (!overridingProperties.containsKey(key)) {
                    String value = configurationProperties.getProperty(key);
                    velocityEngine.setProperty(key, value);
                    this.logger.debug("Setting property [{}] = [{}]", key, value);
                }
            }
        }

        // Override the component's static properties with the ones passed in parameter
        if (overridingProperties != null) {
            for (Enumeration< ? > e = overridingProperties.propertyNames(); e.hasMoreElements();) {
                String key = e.nextElement().toString();
                String value = overridingProperties.getProperty(key);
                velocityEngine.setProperty(key, value);
                this.logger.debug("Overriding property [{}] = [{}]", key, value);
            }
        }
    }

    /**
     * Restore the previous {@code $template} variable, if any, in the velocity context.
     * 
     * @param ica the current velocity context
     * @param currentTemplateScope the current Scope, from which to take the replaced variable
     */
    private void restoreTemplateScope(InternalContextAdapterImpl ica, Scope currentTemplateScope)
    {
        if (currentTemplateScope.getParent() != null) {
            ica.put(TEMPLATE_SCOPE_NAME, currentTemplateScope.getParent());
        } else if (currentTemplateScope.getReplaced() != null) {
            ica.put(TEMPLATE_SCOPE_NAME, currentTemplateScope.getReplaced());
        } else {
            ica.remove(TEMPLATE_SCOPE_NAME);
        }
    }

    @Override
    public boolean evaluate(Context context, Writer out, String templateName, String source)
        throws XWikiVelocityException
    {
        return evaluate(context, out, templateName, new StringReader(source));
    }

    @Override
    public boolean evaluate(Context context, Writer out, String templateName, Reader source)
        throws XWikiVelocityException
    {
        // Ensure that initialization has been called
        if (this.engine == null) {
            throw new XWikiVelocityException("This Velocity Engine has not yet been initialized. "
                + " You must call its initialize() method before you can use it.");
        }

        // We override the default implementation here. See #init(RuntimeServices)
        // for explanations.
        try {
            SimpleNode nodeTree = null;

            // The trick is done here: We use the signature that allows
            // passing a boolean and we pass false, thus preventing Velocity
            // from cleaning the context of its velocimacros even though the
            // config property velocimacro.permissions.allow.inline.local.scope
            // is set to true.
            nodeTree = this.rsvc.parse(source, templateName, false);

            if (nodeTree != null) {
                InternalContextAdapterImpl ica = new InternalContextAdapterImpl(
                    context != null ? context : this.velocityContextFactory.createContext());
                ica.pushCurrentTemplateName(templateName);
                boolean provideTemplateScope = this.rsvc.getBoolean("template.provide.scope.control", true);
                Object templateScopeMarker = new Object();
                Scope templateScope = null;
                if (provideTemplateScope) {
                    Object previous = ica.get(TEMPLATE_SCOPE_NAME);
                    templateScope = new Scope(templateScopeMarker, previous);
                    templateScope.put("templateName", templateName);
                    ica.put(TEMPLATE_SCOPE_NAME, templateScope);
                }
                try {
                    nodeTree.init(ica, this.rsvc);
                    nodeTree.render(ica, out);
                } catch (StopCommand stop) {
                    // Check if we're supposed to stop here or not:
                    // - stop if the template is breaking explicitly on the provided $template
                    // - or stop if this is the topmost evaluation
                    if (!stop.isFor(templateScopeMarker) && ica.getTemplateNameStack().length > 1) {
                        throw stop;
                    }
                } finally {
                    ica.popCurrentTemplateName();
                    if (provideTemplateScope) {
                        restoreTemplateScope(ica, templateScope);
                    }
                }
                return true;
            }

            return false;
        } catch (Exception e) {
            throw new XWikiVelocityException("Failed to evaluate content with id [" + templateName + "]", e);
        }
    }

    /**
     * {@inheritDoc}
     * @since 2.4M2
     */
    @Override
    public void clearMacroNamespace(String templateName)
    {
        this.rsvc.dumpVMNamespace(templateName);
    }

    /**
     * {@inheritDoc}
     * @since 2.4RC1
     */
    @Override
    public void startedUsingMacroNamespace(String namespace)
    {
        synchronized (this.namespaceUsageCount) {
            Integer count = this.namespaceUsageCount.get(namespace);
            if (count == null) {
                count = Integer.valueOf(0);
            }
            count = count + 1;
            this.namespaceUsageCount.put(namespace, count);
        }
    }

    /**
     * {@inheritDoc}
     * @since 2.4RC1
     */
    @Override
    public void stoppedUsingMacroNamespace(String namespace)
    {
        synchronized (this.namespaceUsageCount) {
            Integer count = this.namespaceUsageCount.get(namespace);
            if (count == null) {
                // This shouldn't happen
                this.log(LogChute.WARN_ID, "Wrong usage count for namespace [" + namespace + "]");
                return;
            }
            count = count - 1;
            if (count <= 0) {
                this.namespaceUsageCount.remove(namespace);
                this.clearMacroNamespace(namespace);
            } else {
                this.namespaceUsageCount.put(namespace, count);
            }
        }
    }

    @Override
    public void init(RuntimeServices runtimeServices)
    {
        // We save the RuntimeServices instance in order to be able to override the
        // VelocityEngine.evaluate() method. We need to do this so that it's possible
        // to make macros included with #includeMacros() work even though we're using
        // the Velocity setting:
        // velocimacro.permissions.allow.inline.local.scope = true
        // TODO: Fix this when by rewriting the XWiki.include() implementation so that
        // included Velocity templates are added to the current document before
        // evaluation instead of doing 2 separate executions.
        this.rsvc = runtimeServices;
    }

    @Override
    public void log(int level, String message)
    {
        switch (level) {
            case LogChute.WARN_ID:
                this.logger.warn(message);
                break;
            case LogChute.INFO_ID:
                // Velocity info messages are too verbose, just consider them as debug messages...
                this.logger.debug(message);
                break;
            case LogChute.DEBUG_ID:
                this.logger.debug(message);
                break;
            case LogChute.ERROR_ID:
                this.logger.error(message);
                break;
            default:
                this.logger.debug(message);
                break;
        }
    }

    @Override
    public void log(int level, String message, Throwable throwable)
    {
        switch (level) {
            case LogChute.WARN_ID:
                this.logger.warn(message, throwable);
                break;
            case LogChute.INFO_ID:
                // Velocity info messages are too verbose, just consider them as debug messages...
                this.logger.debug(message, throwable);
                break;
            case LogChute.DEBUG_ID:
                this.logger.debug(message, throwable);
                break;
            case LogChute.ERROR_ID:
                this.logger.error(message, throwable);
                break;
            default:
                this.logger.debug(message, throwable);
                break;
        }
    }

    @Override
    public boolean isLevelEnabled(int level)
    {
        boolean isEnabled;

        switch (level) {
            case LogChute.WARN_ID:
                isEnabled = this.logger.isWarnEnabled();
                break;
            case LogChute.INFO_ID:
                // Velocity info messages are too verbose, just consider them as debug messages...
                isEnabled = this.logger.isDebugEnabled();
                break;
            case LogChute.DEBUG_ID:
                isEnabled = this.logger.isDebugEnabled();
                break;
            case LogChute.ERROR_ID:
                isEnabled = this.logger.isErrorEnabled();
                break;
            default:
                isEnabled = this.logger.isDebugEnabled();
                break;
        }

        return isEnabled;
    }
}
