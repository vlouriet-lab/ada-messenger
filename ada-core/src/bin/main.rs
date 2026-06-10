use ada_core::{api::ADAEvent, ADAConfig, ADACore};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // Initialize logging
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("ada_core=info".parse()?))
        .init();

    tracing::info!("ADA Messenger Node v{}", ada_core::PROTOCOL_VERSION);

    // Load or create config
    let config = match ADAConfig::load("ada.json") {
        Ok(cfg) => {
            tracing::info!("Loaded config from ada.json");
            cfg
        }
        Err(_) => {
            tracing::info!("Using default config");
            let cfg = ADAConfig::default();
            let _ = cfg.save("ada.json");
            cfg
        }
    };

    // Create core
    let display_name = std::env::var("ADA_NAME").unwrap_or_else(|_| "ADA Node".into());
    let core = ADACore::new(config, &display_name).await?;

    // Take event receiver
    let mut events = core.take_events().await.expect("Events already taken");

    // Start the core
    core.start().await?;

    tracing::info!("Local Peer ID: {}", core.peer_id());

    // Print our public bundle
    let bundle = core.public_bundle();
    tracing::info!("Display name: {}", bundle.display_name);
    tracing::info!("DH public key: {}", hex::encode(bundle.dh_public));

    // Simple CLI: read commands from stdin
    let core_clone = core.clone();
    tokio::spawn(async move {
        use tokio::io::{AsyncBufReadExt, BufReader};
        let stdin = tokio::io::stdin();
        let mut reader = BufReader::new(stdin);
        let mut line = String::new();

        println!("\nADA Node ready. Commands:");
        println!("  msg <peer_id_base64> <text>  - Send text message");
        println!("  groups                        - List groups");
        println!("  group <name>                  - Create group");
        println!("  convs                         - List conversations");
        println!("  quit                          - Exit\n");

        loop {
            line.clear();
            if reader.read_line(&mut line).await.unwrap_or(0) == 0 {
                break;
            }
            let line = line.trim();
            let parts: Vec<&str> = line.splitn(3, ' ').collect();

            match parts.as_slice() {
                ["msg", peer_b64, text] => {
                    match ada_core::identity::PeerId::from_base64(peer_b64) {
                        Ok(peer) => match core_clone.send_text(&peer, text.to_string()).await {
                            Ok(id) => println!("Sent message {}", hex::encode(id)),
                            Err(e) => println!("Error: {}", e),
                        },
                        Err(_) => println!("Invalid peer ID"),
                    }
                }
                ["groups"] => {
                    let groups = core_clone.list_groups();
                    if groups.is_empty() {
                        println!("No groups");
                    }
                    for g in groups {
                        println!(
                            "  {} - {} ({} members)",
                            hex::encode(g.id),
                            g.name,
                            g.member_count()
                        );
                    }
                }
                ["group", name] => {
                    let (id, topic) = core_clone.create_group(name).await;
                    println!("Created group: {} (topic: {})", hex::encode(id), topic);
                }
                ["convs"] => {
                    for conv in core_clone.conversations() {
                        println!("  {:?} - unread: {}", conv.id, conv.unread_count);
                    }
                }
                ["quit"] => break,
                _ => println!("Unknown command: {}", line),
            }
        }
    });

    // Event loop
    tracing::info!("Listening for events...");
    while let Some(event) = events.recv().await {
        match event {
            ADAEvent::MessageReceived(msg) => {
                if let ada_core::messaging::types::MessageKind::Text(text) = &msg.kind {
                    println!("\n[MSG from {}]: {}", msg.sender, text);
                }
            }
            ADAEvent::PeerOnline(peer) => {
                tracing::info!("Peer online: {}", peer);
            }
            ADAEvent::PeerOffline(peer) => {
                tracing::info!("Peer offline: {}", peer);
            }
            ADAEvent::IncomingCall {
                call_id,
                from,
                has_video,
                ..
            } => {
                println!(
                    "\n[CALL] Incoming {} call from {}",
                    if has_video { "video" } else { "audio" },
                    from
                );
                println!("  Call ID: {}", hex::encode(call_id));
                println!("  Answer with: answer {}", hex::encode(call_id));
            }
            ADAEvent::Error(e) => {
                tracing::error!("ADA error: {}", e);
            }
            ADAEvent::NetworkConnected => {
                tracing::info!("Network connected");
            }
            _ => {}
        }
    }

    core.stop().await;
    Ok(())
}
