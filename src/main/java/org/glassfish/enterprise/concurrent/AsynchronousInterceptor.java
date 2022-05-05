/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) [2022] Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.enterprise.concurrent;

import jakarta.annotation.Priority;
import jakarta.enterprise.concurrent.Asynchronous;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import org.glassfish.enterprise.concurrent.internal.ManagedCompletableFuture;

/**
 * Interceptor for @Asynchronous.
 *
 * @author Petr Aubrecht <aubrecht@asoftware.cz>
 */
@Interceptor
@Asynchronous
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 5)
public class AsynchronousInterceptor {
    static final Logger log = Logger.getLogger(AsynchronousInterceptor.class.getName());

    @AroundInvoke
    public Object intercept(InvocationContext context) throws Exception {
        String executor = context.getMethod().getAnnotation(Asynchronous.class).executor();
        executor = executor != null ? executor : "java:comp/DefaultManagedExecutorService"; // provide default value if there is none
        log.log(Level.FINE, "AsynchronousInterceptor.intercept around asynchronous method {0}, executor=''{1}''", new Object[]{context.getMethod(), executor});
        ManagedExecutorService mes;
        try {
            Object lookupMes = new InitialContext().lookup(executor);
            if (lookupMes == null) {
                throw new RejectedExecutionException("ManagedExecutorService with jndi '" + executor + "' not found!");
            }
            if (!(lookupMes instanceof ManagedExecutorService)) {
                throw new RejectedExecutionException("ManagedExecutorService with jndi '" + executor + "' must be of type jakarta.enterprise.concurrent.ManagedExecutorService, found " + lookupMes.getClass().getName());
            }
            mes = (ManagedExecutorService) lookupMes;
        } catch (NamingException ex) {
            throw new RejectedExecutionException("ManagedExecutorService with jndi '" + executor + "' not found as requested by asynchronous method " + context.getMethod());
        }
        CompletableFuture<Object> resultFuture = new ManagedCompletableFuture<>(mes);
        mes.submit(() -> {
            Asynchronous.Result.setFuture(resultFuture);
            CompletableFuture<Object> returnedFuture = resultFuture;
            try {
                // the asynchronous method is responsible for calling Asynchronous.Result.complete()
                returnedFuture = (CompletableFuture<Object>) context.proceed();
            } catch (Exception ex) {
                resultFuture.completeExceptionally(ex);
            } finally {
                // Check if Asynchronous.Result is not completed?
                if (!returnedFuture.isDone()) {
                    log.log(Level.SEVERE, "Method annotated with @Asynchronous did not call Asynchronous.Result.complete() at its end: {0}", context.getMethod().toString());
                    Asynchronous.Result.getFuture().cancel(true);
                }
                if (returnedFuture != Asynchronous.Result.getFuture()) {
                    // if the asynchronous methods returns a different future, use this to complete the resultFuture
                    try {
                        resultFuture.complete(returnedFuture.get());
                    } catch (InterruptedException | ExecutionException e) {
                        resultFuture.completeExceptionally(e);
                    }
                }
                // cleanup after asynchronous call
                Asynchronous.Result.setFuture(null);
            }
        });
        return resultFuture;
    }
}
