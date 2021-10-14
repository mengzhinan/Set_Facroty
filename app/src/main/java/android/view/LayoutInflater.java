/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.util.Xml;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.test.set_factory.R;

import dalvik.system.PathClassLoader;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Objects;

public abstract class LayoutInflater {

    private static final String TAG = LayoutInflater.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final String COMPILED_VIEW_DEX_FILE_NAME = "/compiled_view.dex";
    /**
     * Whether or not we use the precompiled layout.
     */
    private static final String USE_PRECOMPILED_LAYOUT = "view.precompiled_layout_enabled";

    /** Empty stack trace used to avoid log spam in re-throw exceptions. */
    private static final StackTraceElement[] EMPTY_STACK_TRACE = new StackTraceElement[0];

    protected final Context mContext;
    private boolean mFactorySet;
    private Factory mFactory;
    private Factory2 mFactory2;
    private Factory2 mPrivateFactory;
    private Filter mFilter;

    private boolean mUseCompiledView;
    private ClassLoader mPrecompiledClassLoader;

    final Object[] mConstructorArgs = new Object[2];

    static final Class<?>[] mConstructorSignature = new Class[] {
            Context.class, AttributeSet.class};

    private static final HashMap<String, Constructor<? extends View>> sConstructorMap =
            new HashMap<String, Constructor<? extends View>>();

    private HashMap<String, Boolean> mFilterMap;

    private TypedValue mTempValue;

    private static final String TAG_MERGE = "merge";
    private static final String TAG_INCLUDE = "include";
    private static final String TAG_REQUEST_FOCUS = "requestFocus";
    private static final String TAG_TAG = "tag";

    private static final String ATTR_LAYOUT = "layout";

    private static final int[] ATTRS_THEME = new int[] {
            android.R.attr.theme };

    public interface Filter {
        boolean onLoadClass(Class clazz);
    }

    public interface Factory {
        @Nullable
        View onCreateView(@NonNull String name, @NonNull Context context,
                          @NonNull AttributeSet attrs);
    }

    public interface Factory2 extends Factory {
        @Nullable
        View onCreateView(@Nullable View parent, @NonNull String name,
                          @NonNull Context context, @NonNull AttributeSet attrs);
    }

    private static class FactoryMerger implements Factory2 {
        private final Factory mF1, mF2;
        private final Factory2 mF12, mF22;

        FactoryMerger(Factory f1, Factory2 f12, Factory f2, Factory2 f22) {
            mF1 = f1;
            mF2 = f2;
            mF12 = f12;
            mF22 = f22;
        }

        @Nullable
        public View onCreateView(@NonNull String name, @NonNull Context context,
                                 @NonNull AttributeSet attrs) {
            View v = mF1.onCreateView(name, context, attrs);
            if (v != null) return v;
            return mF2.onCreateView(name, context, attrs);
        }

        @Nullable
        public View onCreateView(@Nullable View parent, @NonNull String name,
                                 @NonNull Context context, @NonNull AttributeSet attrs) {
            View v = mF12 != null ? mF12.onCreateView(parent, name, context, attrs)
                    : mF1.onCreateView(name, context, attrs);
            if (v != null) return v;
            return mF22 != null ? mF22.onCreateView(parent, name, context, attrs)
                    : mF2.onCreateView(name, context, attrs);
        }
    }

    protected LayoutInflater(Context context) {
        mContext = context;
        initPrecompiledViews();
    }

    protected LayoutInflater(LayoutInflater original, Context newContext) {
        mContext = newContext;
        mFactory = original.mFactory;
        mFactory2 = original.mFactory2;
        mPrivateFactory = original.mPrivateFactory;
        setFilter(original.mFilter);
        initPrecompiledViews();
    }

    public static LayoutInflater from(Context context) {
        LayoutInflater LayoutInflater =
                (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (LayoutInflater == null) {
            throw new AssertionError("LayoutInflater not found.");
        }
        return LayoutInflater;
    }

    public abstract LayoutInflater cloneInContext(Context newContext);

    public Context getContext() {
        return mContext;
    }

    public final Factory getFactory() {
        return mFactory;
    }

    public final Factory2 getFactory2() {
        return mFactory2;
    }

    public void setFactory(Factory factory) {
        if (mFactorySet) {
            throw new IllegalStateException("A factory has already been set on this LayoutInflater");
        }
        if (factory == null) {
            throw new NullPointerException("Given factory can not be null");
        }
        mFactorySet = true;
        if (mFactory == null) {
            mFactory = factory;
        } else {
            mFactory = new FactoryMerger(factory, null, mFactory, mFactory2);
        }
    }

    public void setFactory2(Factory2 factory) {
        if (mFactorySet) {
            throw new IllegalStateException("A factory has already been set on this LayoutInflater");
        }
        if (factory == null) {
            throw new NullPointerException("Given factory can not be null");
        }
        mFactorySet = true;
        if (mFactory == null) {
            mFactory = mFactory2 = factory;
        } else {
            mFactory = mFactory2 = new FactoryMerger(factory, factory, mFactory, mFactory2);
        }
    }

    public void setPrivateFactory(Factory2 factory) {
        if (mPrivateFactory == null) {
            mPrivateFactory = factory;
        } else {
            mPrivateFactory = new FactoryMerger(factory, factory, mPrivateFactory, mPrivateFactory);
        }
    }

    public Filter getFilter() {
        return mFilter;
    }

    public void setFilter(Filter filter) {
        mFilter = filter;
        if (filter != null) {
            mFilterMap = new HashMap<String, Boolean>();
        }
    }

    private void initPrecompiledViews() {
        // Precompiled layouts are not supported in this release.
        boolean enabled = false;
        initPrecompiledViews(enabled);
    }

    private void initPrecompiledViews(boolean enablePrecompiledViews) {
        mUseCompiledView = enablePrecompiledViews;

        if (!mUseCompiledView) {
            mPrecompiledClassLoader = null;
            return;
        }

        // Make sure the application allows code generation
//        ApplicationInfo appInfo = mContext.getApplicationInfo();
//        if (appInfo.isEmbeddedDexUsed() || appInfo.isPrivilegedApp()) {
//            mUseCompiledView = false;
//            return;
//        }

        // Try to load the precompiled layout file.
        try {
            mPrecompiledClassLoader = mContext.getClassLoader();
            String dexFile = mContext.getCodeCacheDir() + COMPILED_VIEW_DEX_FILE_NAME;
            if (new File(dexFile).exists()) {
                mPrecompiledClassLoader = new PathClassLoader(dexFile, mPrecompiledClassLoader);
            } else {
                // If the precompiled layout file doesn't exist, then disable precompiled
                // layouts.
                mUseCompiledView = false;
            }
        } catch (Throwable e) {
            if (DEBUG) {
                Log.e(TAG, "Failed to initialized precompiled views layouts", e);
            }
            mUseCompiledView = false;
        }
        if (!mUseCompiledView) {
            mPrecompiledClassLoader = null;
        }
    }

    /**
     * Inflate a new view hierarchy from the specified xml resource. Throws
     * {@link InflateException} if there is an error.
     *
     * @param resource ID for an XML layout resource to load (e.g.,
     *        <code>R.layout.main_page</code>)
     * @param root Optional view to be the parent of the generated hierarchy.
     * @return The root View of the inflated hierarchy. If root was supplied,
     *         this is the root View; otherwise it is the root of the inflated
     *         XML file.
     */
    public View inflate(@LayoutRes int resource, @Nullable ViewGroup root) {
        return inflate(resource, root, root != null);
    }

    /**
     * Inflate a new view hierarchy from the specified xml node. Throws
     * {@link InflateException} if there is an error. *
     * <p>
     * <em><strong>Important</strong></em>&nbsp;&nbsp;&nbsp;For performance
     * reasons, view inflation relies heavily on pre-processing of XML files
     * that is done at build time. Therefore, it is not currently possible to
     * use LayoutInflater with an XmlPullParser over a plain XML file at runtime.
     *
     * @param parser XML dom node containing the description of the view
     *        hierarchy.
     * @param root Optional view to be the parent of the generated hierarchy.
     * @return The root View of the inflated hierarchy. If root was supplied,
     *         this is the root View; otherwise it is the root of the inflated
     *         XML file.
     */
    public View inflate(XmlPullParser parser, @Nullable ViewGroup root) {
        return inflate(parser, root, root != null);
    }

    /**
     * Inflate a new view hierarchy from the specified xml resource. Throws
     * {@link InflateException} if there is an error.
     *
     * @param resource ID for an XML layout resource to load (e.g.,
     *        <code>R.layout.main_page</code>)
     * @param root Optional view to be the parent of the generated hierarchy (if
     *        <em>attachToRoot</em> is true), or else simply an object that
     *        provides a set of LayoutParams values for root of the returned
     *        hierarchy (if <em>attachToRoot</em> is false.)
     * @param attachToRoot Whether the inflated hierarchy should be attached to
     *        the root parameter? If false, root is only used to create the
     *        correct subclass of LayoutParams for the root view in the XML.
     * @return The root View of the inflated hierarchy. If root was supplied and
     *         attachToRoot is true, this is root; otherwise it is the root of
     *         the inflated XML file.
     */
    public View inflate(@LayoutRes int resource, @Nullable ViewGroup root, boolean attachToRoot) {
        final Resources res = getContext().getResources();
        if (DEBUG) {
            Log.d(TAG, "INFLATING from resource: \"" + res.getResourceName(resource) + "\" ("
                    + Integer.toHexString(resource) + ")");
        }

        View view = tryInflatePrecompiled(resource, res, root, attachToRoot);
        if (view != null) {
            return view;
        }
        XmlResourceParser parser = res.getLayout(resource);
        try {
            return inflate(parser, root, attachToRoot);
        } finally {
            parser.close();
        }
    }

    private @Nullable
    View tryInflatePrecompiled(@LayoutRes int resource, Resources res, @Nullable ViewGroup root,
                               boolean attachToRoot) {
        if (!mUseCompiledView) {
            return null;
        }


        // Try to inflate using a precompiled layout.
        String pkg = res.getResourcePackageName(resource);
        String layout = res.getResourceEntryName(resource);

        try {
            Class clazz = Class.forName("" + pkg + ".CompiledView", false, mPrecompiledClassLoader);
            Method inflater = clazz.getMethod(layout, Context.class, int.class);
            View view = (View) inflater.invoke(null, mContext, resource);

            if (view != null && root != null) {
                // We were able to use the precompiled inflater, but now we need to do some work to
                // attach the view to the root correctly.
                XmlResourceParser parser = res.getLayout(resource);
                try {
                    AttributeSet attrs = Xml.asAttributeSet(parser);
                    advanceToRootNode(parser);
                    ViewGroup.LayoutParams params = root.generateLayoutParams(attrs);

                    if (attachToRoot) {
                        root.addView(view, params);
                    } else {
                        view.setLayoutParams(params);
                    }
                } finally {
                    parser.close();
                }
            }

            return view;
        } catch (Throwable e) {
            if (DEBUG) {
                Log.e(TAG, "Failed to use precompiled view", e);
            }
        } finally {
        }
        return null;
    }

    /**
     * Advances the given parser to the first START_TAG. Throws InflateException if no start tag is
     * found.
     */
    private void advanceToRootNode(XmlPullParser parser)
            throws InflateException, IOException, XmlPullParserException {
        // Look for the root node.
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG &&
                type != XmlPullParser.END_DOCUMENT) {
            // Empty
        }

        if (type != XmlPullParser.START_TAG) {
            throw new InflateException(parser.getPositionDescription()
                    + ": No start tag found!");
        }
    }

    /**
     * Inflate a new view hierarchy from the specified XML node. Throws
     * {@link InflateException} if there is an error.
     * <p>
     * <em><strong>Important</strong></em>&nbsp;&nbsp;&nbsp;For performance
     * reasons, view inflation relies heavily on pre-processing of XML files
     * that is done at build time. Therefore, it is not currently possible to
     * use LayoutInflater with an XmlPullParser over a plain XML file at runtime.
     *
     * @param parser XML dom node containing the description of the view
     *        hierarchy.
     * @param root Optional view to be the parent of the generated hierarchy (if
     *        <em>attachToRoot</em> is true), or else simply an object that
     *        provides a set of LayoutParams values for root of the returned
     *        hierarchy (if <em>attachToRoot</em> is false.)
     * @param attachToRoot Whether the inflated hierarchy should be attached to
     *        the root parameter? If false, root is only used to create the
     *        correct subclass of LayoutParams for the root view in the XML.
     * @return The root View of the inflated hierarchy. If root was supplied and
     *         attachToRoot is true, this is root; otherwise it is the root of
     *         the inflated XML file.
     */
    public View inflate(XmlPullParser parser, @Nullable ViewGroup root, boolean attachToRoot) {
        synchronized (mConstructorArgs) {

            final Context inflaterContext = mContext;
            final AttributeSet attrs = Xml.asAttributeSet(parser);
            Context lastContext = (Context) mConstructorArgs[0];
            mConstructorArgs[0] = inflaterContext;
            View result = root;

            try {
                advanceToRootNode(parser);
                final String name = parser.getName();

                if (DEBUG) {
                    System.out.println("**************************");
                    System.out.println("Creating root view: "
                            + name);
                    System.out.println("**************************");
                }

                if (TAG_MERGE.equals(name)) {
                    if (root == null || !attachToRoot) {
                        throw new InflateException("<merge /> can be used only with a valid "
                                + "ViewGroup root and attachToRoot=true");
                    }

                    rInflate(parser, root, inflaterContext, attrs, false);
                } else {
                    // Temp is the root view that was found in the xml
                    final View temp = createViewFromTag(root, name, inflaterContext, attrs);

                    ViewGroup.LayoutParams params = null;

                    if (root != null) {
                        if (DEBUG) {
                            System.out.println("Creating params from root: " +
                                    root);
                        }
                        // Create layout params that match root, if supplied
                        params = root.generateLayoutParams(attrs);
                        if (!attachToRoot) {
                            // Set the layout params for temp if we are not
                            // attaching. (If we are, we use addView, below)
                            temp.setLayoutParams(params);
                        }
                    }

                    if (DEBUG) {
                        System.out.println("-----> start inflating children");
                    }

                    // Inflate all children under temp against its context.
                    rInflateChildren(parser, temp, attrs, true);

                    if (DEBUG) {
                        System.out.println("-----> done inflating children");
                    }

                    // We are supposed to attach all the views we found (int temp)
                    // to root. Do that now.
                    if (root != null && attachToRoot) {
                        root.addView(temp, params);
                    }

                    // Decide whether to return the root that was passed in or the
                    // top view found in xml.
                    if (root == null || !attachToRoot) {
                        result = temp;
                    }
                }

            } catch (XmlPullParserException e) {
                final InflateException ie = new InflateException(e.getMessage(), e);
                ie.setStackTrace(EMPTY_STACK_TRACE);
                throw ie;
            } catch (Exception e) {
                final InflateException ie = new InflateException(
                        getParserStateDescription(inflaterContext, attrs)
                                + ": " + e.getMessage(), e);
                ie.setStackTrace(EMPTY_STACK_TRACE);
                throw ie;
            } finally {
                // Don't retain static reference on context.
                mConstructorArgs[0] = lastContext;
                mConstructorArgs[1] = null;

            }

            return result;
        }
    }

    private static final ClassLoader BOOT_CLASS_LOADER = LayoutInflater.class.getClassLoader();

    private final boolean verifyClassLoader(Constructor<? extends View> constructor) {
        final ClassLoader constructorLoader = constructor.getDeclaringClass().getClassLoader();
        if (constructorLoader == BOOT_CLASS_LOADER) {
            // fast path for boot class loader (most common case?) - always ok
            return true;
        }
        // in all normal cases (no dynamic code loading), we will exit the following loop on the
        // first iteration (i.e. when the declaring classloader is the contexts class loader).
        ClassLoader cl = mContext.getClassLoader();
        do {
            if (constructorLoader == cl) {
                return true;
            }
            cl = cl.getParent();
        } while (cl != null);
        return false;
    }
    /**
     * Low-level function for instantiating a view by name. This attempts to
     * instantiate a view class of the given <var>name</var> found in this
     * LayoutInflater's ClassLoader. To use an explicit Context in the View
     * constructor, use {@link #createView(Context, String, String, AttributeSet)} instead.
     *
     * <p>
     * There are two things that can happen in an error case: either the
     * exception describing the error will be thrown, or a null will be
     * returned. You must deal with both possibilities -- the former will happen
     * the first time createView() is called for a class of a particular name,
     * the latter every time there-after for that class name.
     *
     * @param name The full name of the class to be instantiated.
     * @param attrs The XML attributes supplied for this instance.
     *
     * @return View The newly instantiated view, or null.
     */
    public final View createView(String name, String prefix, AttributeSet attrs)
            throws ClassNotFoundException, InflateException {
        Context context = (Context) mConstructorArgs[0];
        if (context == null) {
            context = mContext;
        }
        return createView(context, name, prefix, attrs);
    }

    /**
     * Low-level function for instantiating a view by name. This attempts to
     * instantiate a view class of the given <var>name</var> found in this
     * LayoutInflater's ClassLoader.
     *
     * <p>
     * There are two things that can happen in an error case: either the
     * exception describing the error will be thrown, or a null will be
     * returned. You must deal with both possibilities -- the former will happen
     * the first time createView() is called for a class of a particular name,
     * the latter every time there-after for that class name.
     *
     * @param viewContext The context used as the context parameter of the View constructor
     * @param name The full name of the class to be instantiated.
     * @param attrs The XML attributes supplied for this instance.
     *
     * @return View The newly instantiated view, or null.
     */
    @Nullable
    public final View createView(@NonNull Context viewContext, @NonNull String name,
                                 @Nullable String prefix, @Nullable AttributeSet attrs)
            throws ClassNotFoundException, InflateException {
        Objects.requireNonNull(viewContext);
        Objects.requireNonNull(name);
        Constructor<? extends View> constructor = sConstructorMap.get(name);
        if (constructor != null && !verifyClassLoader(constructor)) {
            constructor = null;
            sConstructorMap.remove(name);
        }
        Class<? extends View> clazz = null;

        try {

            if (constructor == null) {
                // Class not found in the cache, see if it's real, and try to add it
                clazz = Class.forName(prefix != null ? (prefix + name) : name, false,
                        mContext.getClassLoader()).asSubclass(View.class);

                if (mFilter != null && clazz != null) {
                    boolean allowed = mFilter.onLoadClass(clazz);
                    if (!allowed) {
                        // failNotAllowed(name, prefix, viewContext, attrs);
                    }
                }
                constructor = clazz.getConstructor(mConstructorSignature);
                constructor.setAccessible(true);
                sConstructorMap.put(name, constructor);
            } else {
                // If we have a filter, apply it to cached constructor
                if (mFilter != null) {
                    // Have we seen this name before?
                    Boolean allowedState = mFilterMap.get(name);
                    if (allowedState == null) {
                        // New class -- remember whether it is allowed
                        clazz = Class.forName(prefix != null ? (prefix + name) : name, false,
                                mContext.getClassLoader()).asSubclass(View.class);

                        boolean allowed = clazz != null && mFilter.onLoadClass(clazz);
                        mFilterMap.put(name, allowed);
                        if (!allowed) {
                            // failNotAllowed(name, prefix, viewContext, attrs);
                        }
                    } else if (allowedState.equals(Boolean.FALSE)) {
                        // failNotAllowed(name, prefix, viewContext, attrs);
                    }
                }
            }

            Object lastContext = mConstructorArgs[0];
            mConstructorArgs[0] = viewContext;
            Object[] args = mConstructorArgs;
            args[1] = attrs;

            try {
                final View view = constructor.newInstance(args);
                if (view instanceof ViewStub) {
                    // Use the same context when inflating ViewStub later.
                    final ViewStub viewStub = (ViewStub) view;
                    viewStub.setLayoutInflater(cloneInContext((Context) args[0]));
                }
                return view;
            } finally {
                mConstructorArgs[0] = lastContext;
            }
        } catch (NoSuchMethodException e) {
            final InflateException ie = new InflateException(
                            ": Error inflating class " + (prefix != null ? (prefix + name) : name), e);
            ie.setStackTrace(EMPTY_STACK_TRACE);
            throw ie;

        } catch (ClassCastException e) {
            // If loaded class is not a View subclass
            final InflateException ie = new InflateException(
                    ": Class is not a View " + (prefix != null ? (prefix + name) : name), e);
            ie.setStackTrace(EMPTY_STACK_TRACE);
            throw ie;
        } catch (ClassNotFoundException e) {
            // If loadClass fails, we should propagate the exception.
            throw e;
        } catch (Exception e) {
            final InflateException ie = new InflateException(
                     ": Error inflating class "
                            + (clazz == null ? "<unknown>" : clazz.getName()), e);
            ie.setStackTrace(EMPTY_STACK_TRACE);
            throw ie;
        } finally {
        }
    }

    /**
     * This routine is responsible for creating the correct subclass of View
     * given the xml element name. Override it to handle custom view objects. If
     * you override this in your subclass be sure to call through to
     * super.onCreateView(name) for names you do not recognize.
     *
     * @param name The fully qualified class name of the View to be create.
     * @param attrs An AttributeSet of attributes to apply to the View.
     *
     * @return View The View created.
     */
    protected View onCreateView(String name, AttributeSet attrs)
            throws ClassNotFoundException {
        return createView(name, "android.view.", attrs);
    }

    /**
     * Version of {@link #onCreateView(String, AttributeSet)} that also
     * takes the future parent of the view being constructed.  The default
     * implementation simply calls {@link #onCreateView(String, AttributeSet)}.
     *
     * @param parent The future parent of the returned view.  <em>Note that
     * this may be null.</em>
     * @param name The fully qualified class name of the View to be create.
     * @param attrs An AttributeSet of attributes to apply to the View.
     *
     * @return View The View created.
     */
    protected View onCreateView(View parent, String name, AttributeSet attrs)
            throws ClassNotFoundException {
        return onCreateView(name, attrs);
    }

    /**
     * Version of {@link #onCreateView(View, String, AttributeSet)} that also
     * takes the inflation context.  The default
     * implementation simply calls {@link #onCreateView(View, String, AttributeSet)}.
     *
     * @param viewContext The Context to be used as a constructor parameter for the View
     * @param parent The future parent of the returned view.  <em>Note that
     * this may be null.</em>
     * @param name The fully qualified class name of the View to be create.
     * @param attrs An AttributeSet of attributes to apply to the View.
     *
     * @return View The View created.
     */
    @Nullable
    public View onCreateView(@NonNull Context viewContext, @Nullable View parent,
                             @NonNull String name, @Nullable AttributeSet attrs)
            throws ClassNotFoundException {
        return onCreateView(parent, name, attrs);
    }

    /**
     * Convenience method for calling through to the five-arg createViewFromTag
     * method. This method passes {@code false} for the {@code ignoreThemeAttr}
     * argument and should be used for everything except {@code &gt;include>}
     * tag parsing.
     */
    private View createViewFromTag(View parent, String name, Context context, AttributeSet attrs) {
        return createViewFromTag(parent, name, context, attrs, false);
    }

    /**
     * Creates a view from a tag name using the supplied attribute set.
     * <p>
     * <strong>Note:</strong> Default visibility so the BridgeInflater can
     * override it.
     *
     * @param parent the parent view, used to inflate layout params
     * @param name the name of the XML tag used to define the view
     * @param context the inflation context for the view, typically the
     *                {@code parent} or base layout inflater context
     * @param attrs the attribute set for the XML tag used to define the view
     * @param ignoreThemeAttr {@code true} to ignore the {@code android:theme}
     *                        attribute (if set) for the view being inflated,
     *                        {@code false} otherwise
     */
    View createViewFromTag(View parent, String name, Context context, AttributeSet attrs,
                           boolean ignoreThemeAttr) {
        if (name.equals("view")) {
            name = attrs.getAttributeValue(null, "class");
        }

        // Apply a theme wrapper, if allowed and one is specified.
        if (!ignoreThemeAttr) {
            final TypedArray ta = context.obtainStyledAttributes(attrs, ATTRS_THEME);
            final int themeResId = ta.getResourceId(0, 0);
            if (themeResId != 0) {
                context = new ContextThemeWrapper(context, themeResId);
            }
            ta.recycle();
        }

        try {
            View view = tryCreateView(parent, name, context, attrs);

            if (view == null) {
                final Object lastContext = mConstructorArgs[0];
                mConstructorArgs[0] = context;
                try {
                    if (-1 == name.indexOf('.')) {
                        view = onCreateView(context, parent, name, attrs);
                    } else {
                        view = createView(context, name, null, attrs);
                    }
                } finally {
                    mConstructorArgs[0] = lastContext;
                }
            }

            return view;
        } catch (InflateException e) {
            throw e;

        } catch (ClassNotFoundException e) {
            final InflateException ie = new InflateException(
                            ": Error inflating class " + name, e);
            ie.setStackTrace(EMPTY_STACK_TRACE);
            throw ie;

        } catch (Exception e) {
            final InflateException ie = new InflateException(
                            ": Error inflating class " + name, e);
            ie.setStackTrace(EMPTY_STACK_TRACE);
            throw ie;
        }
    }

    /**
     * Tries to create a view from a tag name using the supplied attribute set.
     *
     * This method gives the factory provided by {@link LayoutInflater#setFactory} and
     * {@link LayoutInflater#setFactory2} a chance to create a view. However, it does not apply all
     * of the general view creation logic, and thus may return {@code null} for some tags. This
     * method is used by {@link LayoutInflater#inflate} in creating {@code View} objects.
     *
     * @hide for use by precompiled layouts.
     *
     * @param parent the parent view, used to inflate layout params
     * @param name the name of the XML tag used to define the view
     * @param context the inflation context for the view, typically the
     *                {@code parent} or base layout inflater context
     * @param attrs the attribute set for the XML tag used to define the view
     */
    @Nullable
    public final View tryCreateView(@Nullable View parent, @NonNull String name,
                                    @NonNull Context context,
                                    @NonNull AttributeSet attrs) {
        View view;
        if (mFactory2 != null) {
            view = mFactory2.onCreateView(parent, name, context, attrs);
        } else if (mFactory != null) {
            view = mFactory.onCreateView(name, context, attrs);
        } else {
            view = null;
        }

        if (view == null && mPrivateFactory != null) {
            view = mPrivateFactory.onCreateView(parent, name, context, attrs);
        }

        return view;
    }

    /**
     * Recursive method used to inflate internal (non-root) children. This
     * method calls through to {@link #rInflate} using the parent context as
     * the inflation context.
     * <strong>Note:</strong> Default visibility so the BridgeInflater can
     * call it.
     */
    final void rInflateChildren(XmlPullParser parser, View parent, AttributeSet attrs,
                                boolean finishInflate) throws XmlPullParserException, IOException {
        rInflate(parser, parent, parent.getContext(), attrs, finishInflate);
    }

    /**
     * Recursive method used to descend down the xml hierarchy and instantiate
     * views, instantiate their children, and then call onFinishInflate().
     * <p>
     * <strong>Note:</strong> Default visibility so the BridgeInflater can
     * override it.
     */
    void rInflate(XmlPullParser parser, View parent, Context context,
                  AttributeSet attrs, boolean finishInflate) throws XmlPullParserException, IOException {

        final int depth = parser.getDepth();
        int type;
        boolean pendingRequestFocus = false;

        while (((type = parser.next()) != XmlPullParser.END_TAG ||
                parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final String name = parser.getName();

            if (TAG_REQUEST_FOCUS.equals(name)) {
                pendingRequestFocus = true;
            } else if (TAG_TAG.equals(name)) {
                parseViewTag(parser, parent, attrs);
            } else if (TAG_INCLUDE.equals(name)) {
                if (parser.getDepth() == 0) {
                    throw new InflateException("<include /> cannot be the root element");
                }
                parseInclude(parser, context, parent, attrs);
            } else if (TAG_MERGE.equals(name)) {
                throw new InflateException("<merge /> must be the root element");
            } else {
                final View view = createViewFromTag(parent, name, context, attrs);
                final ViewGroup viewGroup = (ViewGroup) parent;
                final ViewGroup.LayoutParams params = viewGroup.generateLayoutParams(attrs);
                rInflateChildren(parser, view, attrs, true);
                viewGroup.addView(view, params);
            }
        }

        if (pendingRequestFocus) {
            parent.restoreDefaultFocus();
        }

        if (finishInflate) {
            parent.onFinishInflate();
        }
    }

    /**
     * Parses a <code>&lt;tag&gt;</code> element and sets a keyed tag on the
     * containing View.
     */
    private void parseViewTag(XmlPullParser parser, View view, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        final Context context = view.getContext();
        final TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.ViewTag);
        final int key = ta.getResourceId(R.styleable.ViewTag_id, 0);
        final CharSequence value = ta.getText(R.styleable.ViewTag_value);
        view.setTag(key, value);
        ta.recycle();
    }

    private void parseInclude(XmlPullParser parser, Context context, View parent,
                              AttributeSet attrs) throws XmlPullParserException, IOException {

        int type;
        if (!(parent instanceof ViewGroup)) {
            throw new InflateException("<include /> can only be used inside of a ViewGroup");
        }

        // Apply a theme wrapper, if requested. This is sort of a weird
        // edge case, since developers think the <include> overwrites
        // values in the AttributeSet of the included View. So, if the
        // included View has a theme attribute, we'll need to ignore it.
        final TypedArray ta = context.obtainStyledAttributes(attrs, ATTRS_THEME);
        final int themeResId = ta.getResourceId(0, 0);
        final boolean hasThemeOverride = themeResId != 0;
        if (hasThemeOverride) {
            context = new ContextThemeWrapper(context, themeResId);
        }
        ta.recycle();

        // If the layout is pointing to a theme attribute, we have to
        // massage the value to get a resource identifier out of it.
        int layout = attrs.getAttributeResourceValue(null, ATTR_LAYOUT, 0);
        if (layout == 0) {
            final String value = attrs.getAttributeValue(null, ATTR_LAYOUT);
            if (value == null || value.length() <= 0) {
                throw new InflateException("You must specify a layout in the"
                        + " include tag: <include layout=\"@layout/layoutID\" />");
            }

            // Attempt to resolve the "?attr/name" string to an attribute
            // within the default (e.g. application) package.
            layout = context.getResources().getIdentifier(
                    value.substring(1), "attr", context.getPackageName());

        }

        // The layout might be referencing a theme attribute.
        if (mTempValue == null) {
            mTempValue = new TypedValue();
        }
        if (layout != 0 && context.getTheme().resolveAttribute(layout, mTempValue, true)) {
            layout = mTempValue.resourceId;
        }

        if (layout == 0) {
            final String value = attrs.getAttributeValue(null, ATTR_LAYOUT);
            throw new InflateException("You must specify a valid layout "
                    + "reference. The layout ID " + value + " is not valid.");
        }

        final View precompiled = tryInflatePrecompiled(layout, context.getResources(),
                (ViewGroup) parent, /*attachToRoot=*/true);
        if (precompiled == null) {
            final XmlResourceParser childParser = context.getResources().getLayout(layout);

            try {
                final AttributeSet childAttrs = Xml.asAttributeSet(childParser);

                while ((type = childParser.next()) != XmlPullParser.START_TAG &&
                        type != XmlPullParser.END_DOCUMENT) {
                    // Empty.
                }

                if (type != XmlPullParser.START_TAG) {
                    throw new InflateException(
                            ": No start tag found!");
                }

                final String childName = childParser.getName();

                if (TAG_MERGE.equals(childName)) {
                    // The <merge> tag doesn't support android:theme, so
                    // nothing special to do here.
                    rInflate(childParser, parent, context, childAttrs, false);
                } else {
                    final View view = createViewFromTag(parent, childName,
                            context, childAttrs, hasThemeOverride);
                    final ViewGroup group = (ViewGroup) parent;

                    final TypedArray a = context.obtainStyledAttributes(
                            attrs, R.styleable.Include);
                    final int id = a.getResourceId(R.styleable.Include_id, View.NO_ID);
                    final int visibility = a.getInt(R.styleable.Include_visibility, -1);
                    a.recycle();

                    // We try to load the layout params set in the <include /> tag.
                    // If the parent can't generate layout params (ex. missing width
                    // or height for the framework ViewGroups, though this is not
                    // necessarily true of all ViewGroups) then we expect it to throw
                    // a runtime exception.
                    // We catch this exception and set localParams accordingly: true
                    // means we successfully loaded layout params from the <include>
                    // tag, false means we need to rely on the included layout params.
                    ViewGroup.LayoutParams params = null;
                    try {
                        params = group.generateLayoutParams(attrs);
                    } catch (RuntimeException e) {
                        // Ignore, just fail over to child attrs.
                    }
                    if (params == null) {
                        params = group.generateLayoutParams(childAttrs);
                    }
                    view.setLayoutParams(params);

                    // Inflate all children.
                    rInflateChildren(childParser, view, childAttrs, true);

                    if (id != View.NO_ID) {
                        view.setId(id);
                    }

                    switch (visibility) {
                        case 0:
                            view.setVisibility(View.VISIBLE);
                            break;
                        case 1:
                            view.setVisibility(View.INVISIBLE);
                            break;
                        case 2:
                            view.setVisibility(View.GONE);
                            break;
                    }

                    group.addView(view);
                }
            } finally {
                childParser.close();
            }
        }
    }

}
