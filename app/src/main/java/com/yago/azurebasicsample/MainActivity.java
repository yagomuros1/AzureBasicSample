package com.yago.azurebasicsample;

import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;

import androidx.appcompat.app.AppCompatActivity;

import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.MaterialFactory;
import com.google.ar.sceneform.rendering.Renderable;
import com.google.ar.sceneform.rendering.ShapeFactory;
import com.google.ar.sceneform.ux.ArFragment;
import com.microsoft.azure.spatialanchors.AnchorLocateCriteria;
import com.microsoft.azure.spatialanchors.CloudSpatialAnchor;
import com.microsoft.azure.spatialanchors.CloudSpatialAnchorSession;
import com.microsoft.azure.spatialanchors.LocateAnchorStatus;
import com.microsoft.azure.spatialanchors.SessionLogLevel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ejemplo basado en la documentación:
 * <p>
 * https://docs.microsoft.com/en-us/azure/spatial-anchors/tutorials/tutorial-new-android-app
 */

public class MainActivity extends AppCompatActivity {

    private boolean tapExecuted = false;
    private final Object syncTaps = new Object();
    private ArFragment arFragment;
    private AnchorNode anchorNode;
    private Renderable nodeRenderable = null;
    private float recommendedSessionProgress = 0f;

    private ArSceneView sceneView;
    private CloudSpatialAnchorSession cloudSession;

    private String anchorId = null;
    private boolean scanningForUpload = false;
    private final Object syncSessionProgress = new Object();
    private ExecutorService executorService = Executors.newSingleThreadExecutor();


    /**
     * Este código enlazará un cliente de escucha, llamado handleTap(), que se detectará cuando el
     * toque la pantalla del dispositivo. Si da la casualidad de que el toque se produce en una
     * superficie del mundo real que ya se ha reconocido en el seguimiento de ARCore,
     * se ejecutará el cliente de escucha.
     */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.ux_fragment);

        if (this.arFragment != null) {
            this.arFragment.setOnTapArPlaneListener(this::handleTap);
            this.sceneView = arFragment.getArSceneView();
        }

        /**
         * los fotogramas de la fuente de la cámara se envían al SDK de Azure Spatial Anchors
         * para su procesamiento.
         * */
        Scene scene = sceneView.getScene();
        scene.addOnUpdateListener(frameTime -> {
            if (this.cloudSession != null && sceneView.getArFrame() != null) {
                this.cloudSession.processFrame(sceneView.getArFrame());
            }
        });

        initializeSession();
    }

    /**
     * Una vez que se le llama, garantiza que se crea una sesión de Azure Spatial Anchors y que se
     * inicializa correctamente durante el inicio de la aplicación.
     */
    private void initializeSession() {
        if (this.cloudSession != null) {
            this.cloudSession.close();
        }
        this.cloudSession = new CloudSpatialAnchorSession();
        this.cloudSession.setSession(sceneView.getSession());
        this.cloudSession.setLogLevel(SessionLogLevel.Information);
        this.cloudSession.addOnLogDebugListener(args -> Log.d("ASAInfo", args.getMessage()));
        this.cloudSession.addErrorListener(args -> Log.e("ASAError", String.format("%s: %s", args.getErrorCode().name(), args.getErrorMessage())));

        this.cloudSession.addSessionUpdatedListener(args -> {
            synchronized (this.syncSessionProgress) {
                this.recommendedSessionProgress = args.getStatus().getRecommendedForCreateProgress();
                Log.i("ASAInfo", String.format("Session progress: %f", this.recommendedSessionProgress));
                if (!this.scanningForUpload) {
                    return;
                }
            }

            runOnUiThread(() -> {
                synchronized (this.syncSessionProgress) {
                    MaterialFactory.makeOpaqueWithColor(this, new Color(
                            this.recommendedSessionProgress,
                            this.recommendedSessionProgress,
                            this.recommendedSessionProgress))
                            .thenAccept(material -> this.nodeRenderable.setMaterial(material));
                }
            });
        });

        this.cloudSession.addAnchorLocatedListener(args -> {
            if (args.getStatus() == LocateAnchorStatus.Located) {
                runOnUiThread(() -> {
                    this.anchorNode = new AnchorNode();
                    this.anchorNode.setAnchor(args.getAnchor().getLocalAnchor());
                    MaterialFactory.makeOpaqueWithColor(this, new Color(android.graphics.Color.GREEN))
                            .thenAccept(greenMaterial -> {
                                this.nodeRenderable = ShapeFactory.makeSphere(0.1f, new Vector3(0.0f, 0.15f, 0.0f), greenMaterial);
                                this.anchorNode.setRenderable(nodeRenderable);
                                this.anchorNode.setParent(arFragment.getArSceneView().getScene());

                                this.anchorId = null;
                                synchronized (this.syncTaps) {
                                    this.tapExecuted = false;
                                }
                            });
                });
            }
        });


        /** Debemos crear una cuenta aquí -->> https://portal.azure.com/ <<--  para obtener las claves*/
        this.cloudSession.getConfiguration().setAccountId(/* Copy your account Identifier in here */);
        this.cloudSession.getConfiguration().setAccountKey(/* Copy your account Key in here */);
        this.cloudSession.start();
    }


    /**
     * handleTap(), que agrupará t0do. Creará una esfera y la colocará en la ubicación tocada.
     * Esta esfera inicialmente será negra, ya que this.recommendedSessionProgress está establecido
     * en cero en este momento. Este valor se ajustará más adelante.
     * <p>
     * Conectará un anclaje espacial local de Azure a la esfera negra que vamos a colocar
     * en el mundo real.
     */
    protected void handleTap(HitResult hitResult, Plane plane, MotionEvent motionEvent) {
        synchronized (this.syncTaps) {
            if (this.tapExecuted) {
                return;
            }
            this.tapExecuted = true;
        }

        if (this.anchorId != null) {
            if (this.anchorNode.getAnchor() != null) {
                this.anchorNode.getAnchor().detach();
            }
            this.anchorNode.setParent(null);
            this.anchorNode = null;
            initializeSession();
            AnchorLocateCriteria criteria = new AnchorLocateCriteria();
            criteria.setIdentifiers(new String[]{this.anchorId});
            cloudSession.createWatcher(criteria);
            return;
        }

        this.anchorNode = new AnchorNode();
        this.anchorNode.setAnchor(hitResult.createAnchor());
        CloudSpatialAnchor cloudAnchor = new CloudSpatialAnchor();
        cloudAnchor.setLocalAnchor(this.anchorNode.getAnchor());

        MaterialFactory.makeOpaqueWithColor(this, new Color(
                this.recommendedSessionProgress,
                this.recommendedSessionProgress,
                this.recommendedSessionProgress))
                .thenAccept(material -> {
                    this.nodeRenderable = ShapeFactory.makeSphere(0.1f, new Vector3(0.0f, 0.15f, 0.0f), material);
                    this.anchorNode.setRenderable(nodeRenderable);
                    this.anchorNode.setParent(arFragment.getArSceneView().getScene());

                    uploadCloudAnchorAsync(cloudAnchor)
                            .thenAccept(id -> {
                                this.anchorId = id;
                                Log.i("ASAInfo", String.format("Cloud Anchor created: %s", this.anchorId));
                                runOnUiThread(() -> MaterialFactory.makeOpaqueWithColor(this, new Color(android.graphics.Color.BLUE))
                                        .thenAccept(blueMaterial -> {
                                            this.nodeRenderable.setMaterial(blueMaterial);
                                            synchronized (this.syncTaps) {
                                                this.tapExecuted = false;
                                            }
                                        }));
                            });
                });
    }

    private CompletableFuture<String> uploadCloudAnchorAsync(CloudSpatialAnchor anchor) {
        synchronized (this.syncSessionProgress) {
            this.scanningForUpload = true;
        }

        return CompletableFuture.runAsync(() -> {
            try {
                float currentSessionProgress;
                do {
                    synchronized (this.syncSessionProgress) {
                        currentSessionProgress = this.recommendedSessionProgress;
                    }
                    if (currentSessionProgress < 1.0) {
                        Thread.sleep(500);
                    }
                }
                while (currentSessionProgress < 1.0);

                synchronized (this.syncSessionProgress) {
                    this.scanningForUpload = false;
                }
                runOnUiThread(() -> MaterialFactory.makeOpaqueWithColor(this, new Color(android.graphics.Color.YELLOW))
                        .thenAccept(yellowMaterial -> this.nodeRenderable.setMaterial(yellowMaterial)));

                this.cloudSession.createAnchorAsync(anchor).get();
            } catch (InterruptedException | ExecutionException e) {
                Log.e("ASAError", e.toString());
                throw new RuntimeException(e);
            }
        }, executorService).thenApply(ignore -> anchor.getIdentifier());
    }

}