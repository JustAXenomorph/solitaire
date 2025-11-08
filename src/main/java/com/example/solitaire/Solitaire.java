package com.example.solitaire;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Klondike Solitaire GUI using Swing.
 * - Game statistics persistence (solitaire.conf)
 * - Night mode toggle
 * - Sound effects
 * - Drag and drop card movements
 * - Auto-complete when possible
 * - Undo functionality
 */
public class Solitaire extends JFrame {
    private static final long serialVersionUID = 1L;
    
    // Night mode toggle (true = night theme). Mutable so we can toggle at runtime.
    private static boolean NIGHT_MODE = false;
    // shared felt texture used by background
    private static TexturePaint FELT_TEXTURE = createFeltTexture();
    
    // UI elements we need to update when toggling theme
    private JMenuBar menuBar;
    
    // Game state
    private Deck deck;
    private Stack<Card> stockPile = new Stack<>();
    private Stack<Card> wastePile = new Stack<>();
    private Stack<Card>[] foundationPiles = new Stack[4]; // A-K by suit
    private final Stack<Card>[] tableauPiles = new Stack[7]; // Main playing area
    
    // Game statistics
    private int gamesPlayed = 0;
    private int gamesWon = 0;
    private int currentScore = 0;
    private long gameStartTime;
    private boolean gameInProgress = false;
    
    // Undo system
    private Stack<GameState> undoStack = new Stack<>();
    private static final int MAX_UNDO = 20;
    
    // UI components
    private JLabel scoreLabel = new JLabel();
    private JLabel timeLabel = new JLabel();
    private JLabel statsLabel = new JLabel();
    private JLabel statusLabel = new JLabel("Welcome to Solitaire!");
    
    // Card panels
    private CardStackPanel stockPanel;
    private CardStackPanel wastePanel;
    private CardStackPanel[] foundationPanels = new CardStackPanel[4];
    private CardStackPanel[] tableauPanels = new CardStackPanel[7];
    
    // Timer for game time tracking
    private javax.swing.Timer gameTimer;
    
    // Animation and interaction
    private Card draggedCard = null;
    private CardStackPanel dragSource = null;
    private java.util.List<Card> draggedCards = new ArrayList<>();
    private Point currentDragPosition = new Point();
    private boolean isDragging = false;
    
    // Overlay for messages
    private final OverlayPanel overlay = new OverlayPanel();
    private float overlayAlpha = 0f;
    
    // Audio settings
    private boolean audioMuted = false;
    private Map<String, Long> lastSoundTime = new HashMap<>();
    
    // Window bounds persistence
    private int savedWindowX = Integer.MIN_VALUE;
    private int savedWindowY = Integer.MIN_VALUE;
    private int savedWindowW = Integer.MIN_VALUE;
    private int savedWindowH = Integer.MIN_VALUE;
    
    public Solitaire() {
        super("Solitaire");
        loadConfig();
        initGame();
        initUI();
        pack();
        
        // Window sizing and positioning
        if (savedWindowW > 0 && savedWindowH > 0) {
            setSize(savedWindowW, savedWindowH);
        } else {
            setSize(1400, 900); // Larger default size to accommodate spaced layout
        }
        setMinimumSize(new Dimension(1200, 700)); // Adjusted minimum size
        if (savedWindowX != Integer.MIN_VALUE && savedWindowY != Integer.MIN_VALUE) {
            setLocation(savedWindowX, savedWindowY);
        } else {
            setLocationRelativeTo(null);
        }
        
        // Window bounds persistence
        addComponentListener(new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent e) { saveWindowBounds(); }
            @Override public void componentResized(ComponentEvent e) { saveWindowBounds(); }
        });
        
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Start first game
        newGame();
    }
    
    private void initGame() {
        // Initialize foundation piles (Ace to King by suit)
        for (int i = 0; i < 4; i++) {
            foundationPiles[i] = new Stack<>();
        }
        
        // Initialize tableau piles (7 columns)
        for (int i = 0; i < 7; i++) {
            tableauPiles[i] = new Stack<>();
        }
    }
    
    /** Play card movement sound */
    private void playCardMoveSynth() {
        if (audioMuted) return;
        new Thread(() -> {
            try {
                final float sampleRate = 44100f;
                final int durationMs = 80;
                final int totalSamples = (int)((durationMs/1000.0) * sampleRate);
                byte[] buf = new byte[totalSamples * 2];
                
                for (int i = 0; i < totalSamples; i++) {
                    double t = i / sampleRate;
                    double freq = 800 + 200 * Math.sin(t * 40);
                    double env = Math.exp(-t * 15);
                    double sample = Math.sin(2.0 * Math.PI * freq * t) * env * 0.3;
                    
                    int val = (int)Math.round(sample * 32767.0);
                    int idx = i * 2;
                    buf[idx] = (byte)(val & 0xFF);
                    buf[idx+1] = (byte)((val >> 8) & 0xFF);
                }
                
                AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(fmt)) {
                    line.open(fmt);
                    line.start();
                    line.write(buf, 0, buf.length);
                    line.drain();
                    line.stop();
                } catch (LineUnavailableException e) {
                    // Audio not available
                }
            } catch (Throwable t) {
                // Ignore audio errors
            }
        }, "solitaire-audio").start();
    }
    
    /** Play victory fanfare */
    private void playVictorySynth() {
        if (audioMuted) return;
        new Thread(() -> {
            try {
                final float sampleRate = 44100f;
                final int durationMs = 1500;
                final int totalSamples = (int)((durationMs/1000.0) * sampleRate);
                byte[] buf = new byte[totalSamples * 2];
                
                double[] melody = {523, 659, 784, 1047}; // C5, E5, G5, C6
                for (int i = 0; i < totalSamples; i++) {
                    double t = i / sampleRate;
                    int noteIndex = (int)(t * 4) % melody.length;
                    double freq = melody[noteIndex];
                    double env = Math.max(0, 1 - (t * 0.8));
                    
                    double sample = Math.sin(2.0 * Math.PI * freq * t) * env * 0.4;
                    sample += 0.3 * Math.sin(2.0 * Math.PI * freq * 2 * t) * env;
                    
                    int val = (int)Math.round(sample * 32767.0);
                    int idx = i * 2;
                    buf[idx] = (byte)(val & 0xFF);
                    buf[idx+1] = (byte)((val >> 8) & 0xFF);
                }
                
                AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(fmt)) {
                    line.open(fmt);
                    line.start();
                    line.write(buf, 0, buf.length);
                    line.drain();
                    line.stop();
                } catch (LineUnavailableException e) {
                    // Audio not available
                }
            } catch (Throwable t) {
                // Ignore audio errors
            }
        }, "solitaire-audio").start();
    }
    
    private void playSoundDebounced(String key, Runnable playAction) {
        if (audioMuted) return;
        
        final long now = System.currentTimeMillis();
        final long thresholdMs = 100; // shorter threshold for card games
        Long last = lastSoundTime.get(key);
        if (last != null && (now - last) < thresholdMs) {
            return;
        }
        lastSoundTime.put(key, now);
        try { 
            playAction.run(); 
        } catch (Throwable t) { 
            // swallow audio errors
        }
    }
    
    private void saveWindowBounds() {
        if (!isShowing()) return;
        Rectangle b = getBounds();
        savedWindowX = b.x; 
        savedWindowY = b.y; 
        savedWindowW = b.width; 
        savedWindowH = b.height;
        saveConfig();
    }
    
    private void applyTheme() {
        Color fg = NIGHT_MODE ? Color.LIGHT_GRAY : Color.BLACK;
        Color bg = NIGHT_MODE ? new Color(30,30,30) : UIManager.getColor("Panel.background");
        
        // Update labels
        scoreLabel.setForeground(fg);
        timeLabel.setForeground(fg);
        statsLabel.setForeground(fg);
        statusLabel.setForeground(fg);
        
        // Update menu bar
        if (menuBar != null) {
            menuBar.setBackground(bg);
            for (int i = 0; i < menuBar.getMenuCount(); i++) {
                JMenu menu = menuBar.getMenu(i);
                if (menu != null) {
                    menu.setForeground(fg);
                    menu.setBackground(bg);
                    for (int j = 0; j < menu.getItemCount(); j++) {
                        JMenuItem item = menu.getItem(j);
                        if (item != null) {
                            item.setForeground(fg);
                            item.setBackground(bg);
                        }
                    }
                }
            }
        }
        
        // Set fonts to match BlackJack style
        try {
            Font base = new Font("Courier New", Font.PLAIN, 12);
            scoreLabel.setFont(base.deriveFont(Font.BOLD, 15f)); // Slightly larger for better visibility in grey box
            timeLabel.setFont(base.deriveFont(Font.BOLD, 15f)); // Slightly larger for better visibility in grey box
            statsLabel.setFont(base.deriveFont(Font.BOLD, 16f)); // Larger font for stats
            statusLabel.setFont(base.deriveFont(Font.BOLD, 16f));
        } catch (Throwable t) {
            // If font not available, continue with defaults
        }
        
        // Regenerate felt texture
        FELT_TEXTURE = createFeltTexture();
        
        // Repaint everything
        SwingUtilities.invokeLater(() -> {
            getContentPane().repaint();
            if (overlay != null) overlay.repaint();
        });
    }
    
    private void initUI() {
        // Use felt background like BlackJack
        setContentPane(new FeltPanel());
        getContentPane().setLayout(new BorderLayout(8, 8));
        
        // Top panel - statistics and controls
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setOpaque(false);
        
        // Center panel with all game info in a grey box
        JPanel gameInfoPanel = new JPanel();
        gameInfoPanel.setLayout(new BoxLayout(gameInfoPanel, BoxLayout.Y_AXIS));
        gameInfoPanel.setOpaque(true);
        gameInfoPanel.setBackground(new Color(200, 200, 200, 255)); // Fully opaque grey to prevent afterimages
        gameInfoPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createRaisedBevelBorder(),
            BorderFactory.createEmptyBorder(6, 12, 6, 12) // Reduced padding
        ));
        
        // First row: Score and Time
        JPanel scoreTimePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 2)); // Reduced gaps
        scoreTimePanel.setOpaque(true); // Make opaque to prevent transparency issues
        scoreTimePanel.setBackground(new Color(200, 200, 200, 255)); // Match parent background
        scoreLabel.setText("Score: 0");
        timeLabel.setText("Time: 00:00");
        
        // Ensure labels render cleanly to prevent afterimages
        scoreLabel.setOpaque(true);
        timeLabel.setOpaque(true);
        scoreLabel.setBackground(new Color(200, 200, 200, 255)); // Fully opaque to prevent afterimages
        timeLabel.setBackground(new Color(200, 200, 200, 255)); // Fully opaque to prevent afterimages
        
        scoreTimePanel.add(scoreLabel);
        scoreTimePanel.add(timeLabel);
        
        // Second row: Statistics
        JPanel statsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        statsPanel.setOpaque(true); // Make opaque to prevent transparency issues
        statsPanel.setBackground(new Color(200, 200, 200, 255)); // Match parent background
        updateStatsLabel();
        
        // Ensure stats label renders cleanly
        statsLabel.setOpaque(true);
        statsLabel.setBackground(new Color(200, 200, 200, 255)); // Fully opaque to prevent afterimages
        
        statsPanel.add(statsLabel);
        
        gameInfoPanel.add(scoreTimePanel);
        gameInfoPanel.add(statsPanel);
        
        // Status label below the main panels
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        
        // Center the grey box in the top panel
        JPanel centerWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER));
        centerWrapper.setOpaque(false);
        centerWrapper.add(gameInfoPanel);
        
        topPanel.add(centerWrapper, BorderLayout.CENTER);
        
        // Create a wrapper panel to include status label with fixed height
        JPanel topWrapperPanel = new JPanel(new BorderLayout());
        topWrapperPanel.setOpaque(false);
        topWrapperPanel.add(topPanel, BorderLayout.NORTH);
        
        // Add status label in a fixed-height panel
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        statusPanel.setOpaque(false);
        statusPanel.setPreferredSize(new Dimension(0, 25)); // Reduced height for status
        statusPanel.add(statusLabel);
        topWrapperPanel.add(statusPanel, BorderLayout.SOUTH);
        
        add(topWrapperPanel, BorderLayout.NORTH);
        
        // Main game area
        JPanel gamePanel = new JPanel(new BorderLayout());
        gamePanel.setOpaque(false);
        
        // Top row - stock, waste, and foundations
        JPanel topRowPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        topRowPanel.setOpaque(false);
        
        // Stock pile
        stockPanel = new CardStackPanel();
        stockPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        stockPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onStockClicked();
            }
        });
        topRowPanel.add(stockPanel);
        
        // Waste pile
        wastePanel = new CardStackPanel(true); // Use horizontal stacking for waste pile
        wastePanel.setBorder(BorderFactory.createLoweredBevelBorder());
        topRowPanel.add(wastePanel);
        
        // Add some extra spacing between waste and foundations
        topRowPanel.add(Box.createHorizontalStrut(40));
        
        // Foundation piles with more spacing
        for (int i = 0; i < 4; i++) {
            foundationPanels[i] = new CardStackPanel();
            foundationPanels[i].setBorder(BorderFactory.createLoweredBevelBorder());
            topRowPanel.add(foundationPanels[i]);
            // Add spacing between foundation piles except after the last one
            if (i < 3) {
                topRowPanel.add(Box.createHorizontalStrut(15));
            }
        }
        
        gamePanel.add(topRowPanel, BorderLayout.NORTH);
        
        // Tableau (main playing area)
        JPanel tableauPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 10));
        tableauPanel.setOpaque(false);
        
        for (int i = 0; i < 7; i++) {
            tableauPanels[i] = new CardStackPanel();
            tableauPanels[i].setBorder(BorderFactory.createLoweredBevelBorder());
            tableauPanels[i].setPreferredSize(new Dimension(120, 380)); // Reduced height to make room for buttons
            tableauPanel.add(tableauPanels[i]);
        }
        
        gamePanel.add(tableauPanel, BorderLayout.CENTER);
        add(gamePanel, BorderLayout.CENTER);
        
        // Menu bar
        menuBar = new JMenuBar();
        JMenu gameMenu = new JMenu("Game");
        
        JMenuItem newGameItem = new JMenuItem("New Game");
        newGameItem.addActionListener(e -> newGame());
        gameMenu.add(newGameItem);
        
        JMenuItem resetStatsItem = new JMenuItem("Reset Statistics");
        resetStatsItem.addActionListener(e -> resetStatistics());
        gameMenu.add(resetStatsItem);
        
        gameMenu.addSeparator();
        
        JMenuItem undoItem = new JMenuItem("Undo");
        undoItem.addActionListener(e -> undo());
        gameMenu.add(undoItem);
        
        JMenuItem hintItem = new JMenuItem("Hint");
        hintItem.addActionListener(e -> showHint());
        gameMenu.add(hintItem);
        
        JMenuItem autoCompleteItem = new JMenuItem("Auto Complete");
        autoCompleteItem.addActionListener(e -> autoComplete());
        gameMenu.add(autoCompleteItem);
        
        gameMenu.addSeparator();
        
        JCheckBoxMenuItem nightModeItem = new JCheckBoxMenuItem("Night Mode", NIGHT_MODE);
        nightModeItem.addActionListener(e -> {
            NIGHT_MODE = nightModeItem.isSelected();
            FELT_TEXTURE = createFeltTexture();
            applyTheme();
            saveConfig();
        });
        gameMenu.add(nightModeItem);
        
        JCheckBoxMenuItem muteItem = new JCheckBoxMenuItem("Mute", audioMuted);
        muteItem.addActionListener(e -> {
            audioMuted = muteItem.isSelected();
            saveConfig();
        });
        gameMenu.add(muteItem);
        
        menuBar.add(gameMenu);
        setJMenuBar(menuBar);
        
        // Setup overlay
        overlay.setVisible(false);
        getLayeredPane().add(overlay, JLayeredPane.POPUP_LAYER);
        
        // Setup custom glass pane for drag visualization
        setGlassPane(new DragGlassPane());
        
        // Apply initial theme
        applyTheme();
        
        // Setup drag and drop
        setupDragAndDrop();
        
        // Start game timer
        gameTimer = new javax.swing.Timer(1000, e -> updateGameTime());
    }
    
    private void setupDragAndDrop() {
        // Add mouse listeners for drag and drop to waste and tableau panels
        MouseAdapter dragHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                CardStackPanel panel = (CardStackPanel) e.getSource();
                Card card = panel.getCardAt(e.getPoint());
                if (card != null && card.isFaceUp()) {
                    startDrag(panel, card, e.getPoint());
                }
            }
            
            @Override
            public void mouseDragged(MouseEvent e) {
                if (draggedCard != null) {
                    continueDrag(e);
                }
            }
            
            @Override
            public void mouseReleased(MouseEvent e) {
                if (draggedCard != null) {
                    endDrag(e);
                }
            }
            
            @Override
            public void mouseClicked(MouseEvent e) {
                // Double-click to auto-move to foundation
                if (e.getClickCount() == 2) {
                    CardStackPanel panel = (CardStackPanel) e.getSource();
                    Card card = panel.getCardAt(e.getPoint());
                    if (card != null && card.isFaceUp()) {
                        tryAutoMoveToFoundation(panel, card);
                    }
                }
            }
        };
        
        wastePanel.addMouseListener(dragHandler);
        wastePanel.addMouseMotionListener(dragHandler);
        
        for (CardStackPanel panel : tableauPanels) {
            panel.addMouseListener(dragHandler);
            panel.addMouseMotionListener(dragHandler);
        }
    }
    
    private void startDrag(CardStackPanel source, Card card, Point point) {
        dragSource = source;
        draggedCard = card;
        isDragging = true;
        
        // For tableau, allow dragging multiple cards if they form a valid sequence
        draggedCards.clear();
        if (isTableauPanel(source)) {
            Stack<Card> pile = getStackForPanel(source);
            int cardIndex = pile.indexOf(card);
            if (cardIndex >= 0) {
                // Only allow dragging from this card if it's face up
                if (!card.isFaceUp()) {
                    draggedCard = null;
                    dragSource = null;
                    isDragging = false;
                    return;
                }
                // Add all cards from this position to the top
                for (int i = cardIndex; i < pile.size(); i++) {
                    Card c = pile.get(i);
                    if (!c.isFaceUp()) break; // Stop if we hit a face-down card
                    draggedCards.add(c);
                }
            }
        } else {
            // For waste pile, only drag the top card
            draggedCards.add(card);
        }
        
        // Initialize drag position
        currentDragPosition.setLocation(point);
        
        // Enable glass pane for drawing drag feedback
        getRootPane().getGlassPane().setVisible(true);
        getRootPane().getGlassPane().repaint();
    }
    
    private void continueDrag(MouseEvent e) {
        // Update drag position and visual feedback
        if (isDragging) {
            Point componentPoint = e.getPoint();
            Point rootPoint = SwingUtilities.convertPoint(e.getComponent(), componentPoint, getRootPane());
            // Center the card under the cursor by adjusting for card size
            currentDragPosition.setLocation(rootPoint.x - 36, rootPoint.y - 48); // Half card size (72x96)
            getRootPane().getGlassPane().repaint();
        }
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
    }
    
    private void tryAutoMoveToFoundation(CardStackPanel source, Card card) {
        // Try to move the card to an appropriate foundation
        for (int i = 0; i < 4; i++) {
            if (canMoveToFoundation(card, i)) {
                java.util.List<Card> singleCard = new ArrayList<>();
                singleCard.add(card);
                performMove(source, foundationPanels[i], singleCard);
                return;
            }
        }
        statusLabel.setText("Cannot move " + card + " to foundation");
    }
    
    private void endDrag(MouseEvent e) {
        setCursor(Cursor.getDefaultCursor());
        isDragging = false;
        
        // Hide glass pane
        getRootPane().getGlassPane().setVisible(false);
        
        // Convert to content pane coordinates
        Point contentPoint = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), getContentPane());
        Component target = SwingUtilities.getDeepestComponentAt(getContentPane(), contentPoint.x, contentPoint.y);
        
        // Look for CardStackPanel in component hierarchy
        while (target != null && !(target instanceof CardStackPanel)) {
            target = target.getParent();
        }
        
        if (target instanceof CardStackPanel) {
            CardStackPanel targetPanel = (CardStackPanel) target;
            if (targetPanel != dragSource && canDropOn(targetPanel)) {
                performMove(dragSource, targetPanel, draggedCards);
            } else {
                statusLabel.setText("Invalid move");
            }
        } else {
            statusLabel.setText("Drop cancelled");
        }
        
        draggedCard = null;
        dragSource = null;
        draggedCards.clear();
    }
    
    private boolean canDropOn(CardStackPanel target) {
        if (draggedCards.isEmpty()) return false;
        
        Card bottomCard = draggedCards.get(0);
        
        if (isFoundationPanel(target)) {
            // Only allow single cards to foundation
            if (draggedCards.size() != 1) return false;
            return canMoveToFoundation(bottomCard, getFoundationIndex(target));
        } else if (isTableauPanel(target)) {
            return canMoveToTableau(draggedCards, getTableauIndex(target));
        }
        
        return false;
    }
    
    private void performMove(CardStackPanel source, CardStackPanel target, java.util.List<Card> cards) {
        saveGameState(); // For undo
        
        Stack<Card> sourceStack = getStackForPanel(source);
        Stack<Card> targetStack = getStackForPanel(target);
        
        // Remove cards from source
        for (Card card : cards) {
            sourceStack.remove(card);
        }
        
        // Add cards to target
        for (Card card : cards) {
            targetStack.push(card);
        }
        
        // Flip top card of source if it's face down
        if (!sourceStack.isEmpty() && isTableauPanel(source)) {
            Card topCard = sourceStack.peek();
            if (!topCard.isFaceUp()) {
                topCard.setFaceUp(true);
                currentScore += 5; // Points for flipping card
            }
        }
        
        // Update score
        if (isFoundationPanel(target)) {
            currentScore += 10; // Points for moving to foundation
        }
        
        playSoundDebounced("move", this::playCardMoveSynth);
        updateDisplay();
        checkForWin();
        
        // Check for game over after each move
        checkForGameOver();
    }
    
    private void newGame() {
        if (gameInProgress) {
            endGame(false);
        }
        
        // Clear all piles
        stockPile.clear();
        wastePile.clear();
        for (Stack<Card> pile : foundationPiles) {
            pile.clear();
        }
        for (Stack<Card> pile : tableauPiles) {
            pile.clear();
        }
        
        // Create and shuffle deck
        deck = new Deck();
        deck.shuffle();
        
        // Deal tableau (1 face-up on first pile, 2 on second with 1 face-up, etc.)
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j <= i; j++) {
                Card card = deck.deal();
                if (j == i) {
                    card.setFaceUp(true); // Top card face up
                } else {
                    card.setFaceUp(false); // Others face down
                }
                tableauPiles[i].push(card);
            }
        }
        
        // Remaining cards go to stock
        while (!deck.isEmpty()) {
            Card card = deck.deal();
            card.setFaceUp(false);
            stockPile.push(card);
        }
        
        // Reset game state
        currentScore = 0;
        gameStartTime = System.currentTimeMillis();
        gameInProgress = true;
        undoStack.clear();
        
        statusLabel.setText("Game started. Good luck!");
        updateDisplay();
        
        if (gameTimer != null) {
            gameTimer.start();
        }
        
        gamesPlayed++;
        saveConfig();
    }
    
    private void onStockClicked() {
        if (!gameInProgress) return;
        
        saveGameState(); // For undo
        
        if (stockPile.isEmpty()) {
            // Recycle waste pile back to stock
            while (!wastePile.isEmpty()) {
                Card card = wastePile.pop();
                card.setFaceUp(false);
                stockPile.push(card);
            }
            // No penalty for recycling - players can go through the deck as many times as needed
            
            // After recycling, check if game is over (no moves available)
            checkForGameOver();
        } else {
            // Draw 3 cards (or remaining cards if less than 3)
            int cardsToDraw = Math.min(3, stockPile.size());
            for (int i = 0; i < cardsToDraw; i++) {
                Card card = stockPile.pop();
                card.setFaceUp(true);
                wastePile.push(card);
            }
            
            // After drawing cards, check if we can make any moves or if game is over
            if (stockPile.isEmpty()) {
                // We've now seen all cards, check for game over
                checkForGameOver();
            }
        }
        
        playSoundDebounced("stock", this::playCardMoveSynth);
        updateDisplay();
    }
    
    private boolean canMoveToFoundation(Card card, int foundationIndex) {
        Stack<Card> foundation = foundationPiles[foundationIndex];
        
        if (foundation.isEmpty()) {
            return card.getRank() == 1; // Ace
        }
        
        Card topCard = foundation.peek();
        return card.getSuit().equals(topCard.getSuit()) && 
               card.getRank() == topCard.getRank() + 1;
    }
    
    private boolean canMoveToTableau(java.util.List<Card> cards, int tableauIndex) {
        if (cards.isEmpty()) return false;
        
        Card bottomCard = cards.get(0);
        Stack<Card> tableau = tableauPiles[tableauIndex];
        
        // Check if the sequence being moved is valid (alternating colors, descending rank)
        for (int i = 0; i < cards.size() - 1; i++) {
            Card current = cards.get(i);
            Card next = cards.get(i + 1);
            if (current.isRed() == next.isRed() || current.getRank() != next.getRank() + 1) {
                return false;
            }
        }
        
        if (tableau.isEmpty()) {
            return true; // Allow any card to be placed on empty tableau (not just Kings)
        }
        
        Card topCard = tableau.peek();
        return topCard.isFaceUp() && 
               bottomCard.isRed() != topCard.isRed() && 
               bottomCard.getRank() == topCard.getRank() - 1;
    }
    
    private void undo() {
        if (!undoStack.isEmpty()) {
            GameState state = undoStack.pop();
            restoreGameState(state);
            currentScore -= 15; // Small penalty for undo
            updateDisplay();
            statusLabel.setText("Move undone.");
        }
    }
    
    private void showHint() {
        // Simple hint system - look for obvious moves
        String hint = findHint();
        if (hint != null) {
            statusLabel.setText("Hint: " + hint);
        } else {
            statusLabel.setText("No obvious moves available. Try drawing from stock.");
        }
    }
    
    private String findHint() {
        // Check for moves to foundation
        for (int i = 0; i < 7; i++) {
            if (!tableauPiles[i].isEmpty()) {
                Card topCard = tableauPiles[i].peek();
                if (topCard.isFaceUp()) {
                    for (int j = 0; j < 4; j++) {
                        if (canMoveToFoundation(topCard, j)) {
                            return "Move " + topCard + " to foundation";
                        }
                    }
                }
            }
        }
        
        // Check waste pile
        if (!wastePile.isEmpty()) {
            Card wasteTop = wastePile.peek();
            for (int j = 0; j < 4; j++) {
                if (canMoveToFoundation(wasteTop, j)) {
                    return "Move " + wasteTop + " from waste to foundation";
                }
            }
        }
        
        return null;
    }
    
    private void autoComplete() {
        // Auto-complete when all cards are face up and only foundation moves remain
        boolean canAutoComplete = true;
        
        // Check if all tableau cards are face up
        for (Stack<Card> pile : tableauPiles) {
            for (Card card : pile) {
                if (!card.isFaceUp()) {
                    canAutoComplete = false;
                    break;
                }
            }
            if (!canAutoComplete) break;
        }
        
        if (canAutoComplete && stockPile.isEmpty()) {
            performAutoComplete();
        } else {
            statusLabel.setText("Auto-complete not available yet.");
        }
    }
    
    private void performAutoComplete() {
        boolean moved;
        do {
            moved = false;
            
            // Try to move cards to foundation
            for (int i = 0; i < 7; i++) {
                if (!tableauPiles[i].isEmpty()) {
                    Card topCard = tableauPiles[i].peek();
                    for (int j = 0; j < 4; j++) {
                        if (canMoveToFoundation(topCard, j)) {
                            tableauPiles[i].pop();
                            foundationPiles[j].push(topCard);
                            currentScore += 10;
                            moved = true;
                            break;
                        }
                    }
                }
            }
            
            // Try waste pile
            if (!wastePile.isEmpty()) {
                Card wasteTop = wastePile.peek();
                for (int j = 0; j < 4; j++) {
                    if (canMoveToFoundation(wasteTop, j)) {
                        wastePile.pop();
                        foundationPiles[j].push(wasteTop);
                        currentScore += 10;
                        moved = true;
                        break;
                    }
                }
            }
            
            if (moved) {
                updateDisplay();
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }
        } while (moved);
        
        checkForWin();
    }
    
    private void checkForWin() {
        boolean won = true;
        for (Stack<Card> foundation : foundationPiles) {
            if (foundation.size() != 13) {
                won = false;
                break;
            }
        }
        
        if (won) {
            endGame(true);
        }
    }
    
    private void checkForGameOver() {
        if (!gameInProgress) {
            return; // Game already ended
        }
        
        // Check if any moves are available
        if (hasAvailableMoves()) {
            return; // Moves still available
        }
        
        // If stock is empty and waste is empty, definitely game over
        if (stockPile.isEmpty() && wastePile.isEmpty()) {
            endGame(false);
            statusLabel.setText("Game Over - No more moves available!");
            showOverlay("GAME OVER", new Color(100, 0, 0), Color.WHITE, 3000);
            return;
        }
        
        // If stock has cards but no current moves available, 
        // check if going through the remaining stock could help
        if (!stockPile.isEmpty()) {
            // Don't declare game over yet if there are still stock cards to reveal
            return;
        }
        
        // If we've seen all cards (stock empty) and no moves available
        if (stockPile.isEmpty() && !hasAvailableMoves()) {
            endGame(false);
            statusLabel.setText("Game Over - No more moves available!");
            showOverlay("GAME OVER", new Color(100, 0, 0), Color.WHITE, 3000);
        }
    }
    
    private boolean hasAvailableMoves() {
        // Debug: Add some logging to see what's being checked
        int movesFound = 0;
        
        // Check waste pile to foundations
        if (!wastePile.isEmpty()) {
            Card wasteTop = wastePile.peek();
            for (int i = 0; i < 4; i++) {
                if (canMoveToFoundation(wasteTop, i)) {
                    movesFound++;
                    return true;
                }
            }
            
            // Check waste pile to tableau
            for (int i = 0; i < 7; i++) {
                java.util.List<Card> singleCard = new ArrayList<>();
                singleCard.add(wasteTop);
                if (canMoveToTableau(singleCard, i)) {
                    movesFound++;
                    return true;
                }
            }
        }
        
        // Check tableau to foundations
        for (int i = 0; i < 7; i++) {
            if (!tableauPiles[i].isEmpty()) {
                Card topCard = tableauPiles[i].peek();
                if (topCard.isFaceUp()) {
                    for (int j = 0; j < 4; j++) {
                        if (canMoveToFoundation(topCard, j)) {
                            movesFound++;
                            return true;
                        }
                    }
                }
            }
        }
        
        // Check tableau to tableau moves
        for (int i = 0; i < 7; i++) {
            if (!tableauPiles[i].isEmpty()) {
                Card topCard = tableauPiles[i].peek();
                if (topCard.isFaceUp()) {
                    // Check if this card can move to another tableau
                    for (int j = 0; j < 7; j++) {
                        if (i != j) {
                            java.util.List<Card> singleCard = new ArrayList<>();
                            singleCard.add(topCard);
                            if (canMoveToTableau(singleCard, j)) {
                                movesFound++;
                                return true;
                            }
                        }
                    }
                    
                    // Check if we can move sequences from this tableau
                    Stack<Card> pile = tableauPiles[i];
                    for (int k = pile.size() - 1; k >= 0; k--) {
                        Card card = pile.get(k);
                        if (!card.isFaceUp()) break;
                        
                        java.util.List<Card> sequence = new ArrayList<>();
                        for (int l = k; l < pile.size(); l++) {
                            sequence.add(pile.get(l));
                        }
                        
                        // Only check sequences of more than one card to avoid duplicate checking
                        if (sequence.size() > 1) {
                            for (int j = 0; j < 7; j++) {
                                if (i != j && canMoveToTableau(sequence, j)) {
                                    movesFound++;
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // If no moves found, update status for debugging
        if (movesFound == 0) {
            statusLabel.setText("Checking for moves... none found.");
        }
        
        return false; // No moves found
    }
    
    private void endGame(boolean won) {
        gameInProgress = false;
        if (gameTimer != null) {
            gameTimer.stop();
        }
        
        if (won) {
            gamesWon++;
            long gameTime = (System.currentTimeMillis() - gameStartTime) / 1000;
            int timeBonus = Math.max(0, 10000 - (int)gameTime * 2); // Time bonus
            currentScore += timeBonus;
            
            showOverlay("VICTORY!", new Color(0, 100, 0), Color.WHITE, 3000);
            statusLabel.setText("Congratulations! You won! Score: " + currentScore);
            playSoundDebounced("victory", this::playVictorySynth);
        }
        
        updateStatsLabel();
        saveConfig();
    }
    
    private void showOverlay(String text, Color bg, Color fg, int ms) {
        Rectangle r = getLayeredPane().getBounds();
        overlay.setBounds(0, 0, r.width, r.height);
        overlay.setOverlayText(text);
        overlay.setOverlayColors(bg, fg);
        overlayAlpha = 1.0f;
        overlay.setVisible(true);
        overlay.repaint();
        
        javax.swing.Timer fadeTimer = new javax.swing.Timer(50, null);
        fadeTimer.addActionListener(new ActionListener() {
            long start = System.currentTimeMillis();
            @Override
            public void actionPerformed(ActionEvent e) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed > ms) {
                    overlayAlpha = Math.max(0f, overlayAlpha - 0.05f);
                    overlay.repaint();
                    if (overlayAlpha <= 0f) {
                        overlay.setVisible(false);
                        fadeTimer.stop();
                    }
                }
            }
        });
        fadeTimer.start();
    }
    
    private void updateDisplay() {
        // Update card panels
        stockPanel.clear();
        if (!stockPile.isEmpty()) {
            // Always show stock as face-down card back when cards are available
            stockPanel.addCardView(new CardView(stockPile.peek(), false));
        }
        // When stock is empty, the empty slot outline will show (handled by CardStackPanel)
        
        wastePanel.clear();
        if (!wastePile.isEmpty()) {
            // Show up to 3 cards from the waste pile, stacked horizontally
            int cardsToShow = Math.min(3, wastePile.size());
            int startIndex = Math.max(0, wastePile.size() - cardsToShow);
            
            for (int i = 0; i < cardsToShow; i++) {
                Card card = wastePile.get(startIndex + i);
                wastePanel.addCardView(new CardView(card, true));
            }
        }
        
        for (int i = 0; i < 4; i++) {
            foundationPanels[i].clear();
            if (!foundationPiles[i].isEmpty()) {
                foundationPanels[i].addCardView(new CardView(foundationPiles[i].peek(), true));
            }
        }
        
        for (int i = 0; i < 7; i++) {
            tableauPanels[i].clear();
            for (int j = 0; j < tableauPiles[i].size(); j++) {
                Card card = tableauPiles[i].get(j);
                tableauPanels[i].addCardView(new CardView(card, card.isFaceUp()));
            }
        }
        
        // Update score
        scoreLabel.setText("Score: " + currentScore);
        
        repaint();
    }
    
    private void updateGameTime() {
        if (gameInProgress) {
            long elapsed = (System.currentTimeMillis() - gameStartTime) / 1000;
            int minutes = (int) (elapsed / 60);
            int seconds = (int) (elapsed % 60);
            String newTimeText = String.format("Time: %02d:%02d", minutes, seconds);
            
            // Only update if the time text has actually changed to reduce repainting
            if (!newTimeText.equals(timeLabel.getText())) {
                timeLabel.setText(newTimeText);
                
                // Efficient repaint - just the label itself since it's opaque
                timeLabel.repaint();
            }
        }
    }
    
    private void updateStatsLabel() {
        int winPercentage = gamesPlayed > 0 ? (gamesWon * 100 / gamesPlayed) : 0;
        statsLabel.setText(String.format("Games: %d  Won: %d  Win Rate: %d%%", 
                                        gamesPlayed, gamesWon, winPercentage));
    }
    
    private void resetStatistics() {
        int result = JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to reset all game statistics?\nThis will set Games Played and Games Won back to 0.",
            "Reset Statistics",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        );
        
        if (result == JOptionPane.YES_OPTION) {
            gamesPlayed = 0;
            gamesWon = 0;
            updateStatsLabel();
            saveConfig();
            statusLabel.setText("Statistics reset successfully!");
        }
    }
    
    // Helper methods for panel identification
    private boolean isFoundationPanel(CardStackPanel panel) {
        for (CardStackPanel fp : foundationPanels) {
            if (fp == panel) return true;
        }
        return false;
    }
    
    private boolean isTableauPanel(CardStackPanel panel) {
        for (CardStackPanel tp : tableauPanels) {
            if (tp == panel) return true;
        }
        return false;
    }
    
    private int getFoundationIndex(CardStackPanel panel) {
        for (int i = 0; i < foundationPanels.length; i++) {
            if (foundationPanels[i] == panel) return i;
        }
        return -1;
    }
    
    private int getTableauIndex(CardStackPanel panel) {
        for (int i = 0; i < tableauPanels.length; i++) {
            if (tableauPanels[i] == panel) return i;
        }
        return -1;
    }
    
    private Stack<Card> getStackForPanel(CardStackPanel panel) {
        if (panel == wastePanel) return wastePile;
        
        for (int i = 0; i < foundationPanels.length; i++) {
            if (foundationPanels[i] == panel) return foundationPiles[i];
        }
        
        for (int i = 0; i < tableauPanels.length; i++) {
            if (tableauPanels[i] == panel) return tableauPiles[i];
        }
        
        return null;
    }
    
    // Game state management for undo
    private void saveGameState() {
        if (undoStack.size() >= MAX_UNDO) {
            undoStack.remove(0); // Remove oldest state
        }
        undoStack.push(new GameState(this));
    }
    
    private void restoreGameState(GameState state) {
        state.restore(this);
    }
    
    // Configuration persistence
    private void loadConfig() {
        Path p = Paths.get("solitaire.conf");
        if (Files.exists(p)) {
            try {
                java.util.List<String> lines = Files.readAllLines(p);
                for (String line : lines) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        String key = parts[0].trim();
                        String value = parts[1].trim();
                        
                        switch (key) {
                            case "nightMode":
                                NIGHT_MODE = Boolean.parseBoolean(value);
                                break;
                            case "audioMuted":
                                audioMuted = Boolean.parseBoolean(value);
                                break;
                            case "gamesPlayed":
                                gamesPlayed = Integer.parseInt(value);
                                break;
                            case "gamesWon":
                                gamesWon = Integer.parseInt(value);
                                break;
                            case "windowBounds":
                                String[] bounds = value.split(",");
                                if (bounds.length == 4) {
                                    savedWindowX = Integer.parseInt(bounds[0]);
                                    savedWindowY = Integer.parseInt(bounds[1]);
                                    savedWindowW = Integer.parseInt(bounds[2]);
                                    savedWindowH = Integer.parseInt(bounds[3]);
                                }
                                break;
                        }
                    }
                }
            } catch (IOException | NumberFormatException e) {
                // Use defaults if file reading or number parsing fails
            }
        }
    }
    
    private void saveConfig() {
        try {
            StringBuilder content = new StringBuilder();
            content.append("nightMode=").append(NIGHT_MODE).append(System.lineSeparator());
            content.append("audioMuted=").append(audioMuted).append(System.lineSeparator());
            content.append("gamesPlayed=").append(gamesPlayed).append(System.lineSeparator());
            content.append("gamesWon=").append(gamesWon).append(System.lineSeparator());
            content.append("windowBounds=").append(savedWindowX).append(",")
                   .append(savedWindowY).append(",").append(savedWindowW).append(",")
                   .append(savedWindowH).append(System.lineSeparator());
            
            Files.write(Paths.get("solitaire.conf"), content.toString().getBytes());
        } catch (Exception e) {
            // Ignore save errors
        }
    }
    
    // Inner classes (Card, Deck, etc.) similar to BlackJack but adapted for Solitaire
    static class Card {
        private final String suit;
        private final int rank; // 1=Ace, 11=Jack, 12=Queen, 13=King
        private boolean faceUp = false;
        
        Card(String suit, int rank) {
            this.suit = suit;
            this.rank = rank;
        }
        
        public String getSuit() { return suit; }
        public int getRank() { return rank; }
        public boolean isFaceUp() { return faceUp; }
        public void setFaceUp(boolean faceUp) { this.faceUp = faceUp; }
        
        public String getRankString() {
            switch (rank) {
                case 1: return "A";
                case 11: return "J";
                case 12: return "Q";
                case 13: return "K";
                default: return String.valueOf(rank);
            }
        }
        
        public boolean isRed() {
            return suit.equals("♥") || suit.equals("♦");
        }
        
        public boolean isBlack() {
            return suit.equals("♠") || suit.equals("♣");
        }
        
        @Override
        public String toString() {
            return getRankString() + suit;
        }
    }
    
    static class Deck {
        private final java.util.List<Card> cards = new ArrayList<>();
        
        Deck() {
            String[] suits = {"♠", "♥", "♦", "♣"};
            for (String suit : suits) {
                for (int rank = 1; rank <= 13; rank++) {
                    cards.add(new Card(suit, rank));
                }
            }
        }
        
        void shuffle() {
            Collections.shuffle(cards);
        }
        
        Card deal() {
            return cards.isEmpty() ? null : cards.remove(cards.size() - 1);
        }
        
        boolean isEmpty() {
            return cards.isEmpty();
        }
    }
    
    // CardView component similar to BlackJack
    static class CardView extends JComponent {
        private final Card card;
        private boolean faceUp;
        private static final int W = 72, H = 96;
        
        CardView(Card card, boolean faceUp) {
            this.card = card;
            this.faceUp = faceUp;
            setPreferredSize(new Dimension(W, H));
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            // Card background
            g2.setColor(Color.WHITE);
            g2.fillRoundRect(2, 2, W-4, H-4, 8, 8);
            g2.setColor(Color.BLACK);
            g2.drawRoundRect(2, 2, W-4, H-4, 8, 8);
            
            if (faceUp && card != null) {
                // Draw card face with proper corners like real playing cards
                g2.setColor(card.isRed() ? Color.RED : Color.BLACK);
                g2.setFont(new Font("SansSerif", Font.BOLD, 12));
                
                String rankText = card.getRankString();
                String suitText = card.getSuit();
                
                // Top-left corner
                g2.drawString(rankText, 6, 16);
                g2.drawString(suitText, 6, 30);
                
                // Bottom-right corner (mirrored/rotated)
                Graphics2D g2Rotated = (Graphics2D) g2.create();
                g2Rotated.rotate(Math.PI, W/2.0, H/2.0); // Rotate 180 degrees around center
                g2Rotated.drawString(rankText, 6, 16);
                g2Rotated.drawString(suitText, 6, 30);
                g2Rotated.dispose();
                
                // Center symbol for face cards
                if (card.getRank() > 10) {
                    g2.setFont(new Font("SansSerif", Font.BOLD, 24));
                    FontMetrics fm = g2.getFontMetrics();
                    String centerText = card.getRankString();
                    int textW = fm.stringWidth(centerText);
                    int textH = fm.getHeight();
                    g2.drawString(centerText, (W - textW)/2, (H + textH)/2 - 3);
                }
            } else {
                // Improved card back pattern - more consistent and visible
                g2.setColor(new Color(0, 50, 150)); // Darker blue for better visibility
                g2.fillRoundRect(4, 4, W-8, H-8, 6, 6);
                
                // Crosshatch pattern for better visual consistency
                g2.setColor(new Color(100, 150, 255)); // Lighter blue for pattern
                g2.setStroke(new BasicStroke(1));
                
                // Diagonal lines pattern
                for (int i = 0; i < W + H; i += 6) {
                    g2.drawLine(i, 4, i - H + 8, H - 4);
                    g2.drawLine(4, i, W - 4, i - W + 8);
                }
                
                // Border highlight
                g2.setColor(new Color(200, 220, 255));
                g2.setStroke(new BasicStroke(2));
                g2.drawRoundRect(5, 5, W-10, H-10, 4, 4);
            }
            
            g2.dispose();
        }
    }
    
    // CardStackPanel with support for both vertical (tableau) and horizontal (waste) stacking
    static class CardStackPanel extends JComponent {
        private final java.util.List<CardView> cardViews = new ArrayList<>();
        private static final int CARD_W = 72;
        private static final int CARD_H = 96;
        private static final int OVERLAP = 20;
        private static final int HORIZONTAL_OFFSET = 24; // Horizontal offset for waste pile
        private final boolean horizontalStacking;
        
        CardStackPanel() {
            this(false); // Default to vertical stacking
        }
        
        CardStackPanel(boolean horizontalStacking) {
            this.horizontalStacking = horizontalStacking;
            setPreferredSize(new Dimension(CARD_W + 4, CARD_H + 4));
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            
            // Draw empty slot outline
            if (cardViews.isEmpty()) {
                g2.setColor(Color.GRAY);
                g2.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{5}, 0));
                g2.drawRoundRect(2, 2, CARD_W, CARD_H, 8, 8);
            }
            
            g2.dispose();
        }
        
        void addCardView(CardView view) {
            cardViews.add(view);
            add(view);
            layoutCards();
        }
        
        void clear() {
            cardViews.clear();
            removeAll();
            repaint();
        }
        
        private void layoutCards() {
            for (int i = 0; i < cardViews.size(); i++) {
                CardView view = cardViews.get(i);
                if (horizontalStacking) {
                    // Horizontal stacking for waste pile - cards spread right with slight overlap
                    view.setBounds(2 + i * HORIZONTAL_OFFSET, 2, CARD_W, CARD_H);
                } else {
                    // Vertical stacking for tableau - cards spread down
                    view.setBounds(2, 2 + i * OVERLAP, CARD_W, CARD_H);
                }
                // Ensure cards are layered properly with top card on top
                setComponentZOrder(view, cardViews.size() - 1 - i);
            }
            
            // Adjust panel size based on stacking direction
            if (cardViews.size() > 0) {
                if (horizontalStacking) {
                    setPreferredSize(new Dimension(CARD_W + (cardViews.size() - 1) * HORIZONTAL_OFFSET + 4, CARD_H + 4));
                } else {
                    setPreferredSize(new Dimension(CARD_W + 4, CARD_H + (cardViews.size() - 1) * OVERLAP + 4));
                }
            }
            revalidate();
            repaint();
        }
        
        Card getCardAt(Point p) {
            // For horizontal stacking (waste pile), prioritize rightmost (topmost) cards
            // For vertical stacking (tableau), prioritize bottommost visible cards
            if (horizontalStacking) {
                // Check from right to left (last to first) for waste pile
                for (int i = cardViews.size() - 1; i >= 0; i--) {
                    CardView view = cardViews.get(i);
                    Rectangle bounds = view.getBounds();
                    if (bounds.contains(p)) {
                        // Always return the top card for waste pile interaction
                        return cardViews.get(cardViews.size() - 1).card;
                    }
                }
            } else {
                // Original behavior for tableau - return the actual clicked card
                for (int i = cardViews.size() - 1; i >= 0; i--) {
                    CardView view = cardViews.get(i);
                    Rectangle bounds = view.getBounds();
                    if (bounds.contains(p)) {
                        return view.card;
                    }
                }
            }
            return null;
        }
    }
    
    // Overlay panel for messages
    class OverlayPanel extends JComponent {
        private String overlayText = "";
        private Color overlayBg = Color.BLACK;
        private Color overlayFg = Color.WHITE;
        
        void setOverlayText(String text) { this.overlayText = text; }
        void setOverlayColors(Color bg, Color fg) { this.overlayBg = bg; this.overlayFg = fg; }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (overlayText != null && !overlayText.isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // Semi-transparent background
                g2.setColor(new Color(overlayBg.getRed(), overlayBg.getGreen(), overlayBg.getBlue(), 
                                    (int)(overlayAlpha * 200)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                
                // Text
                g2.setColor(new Color(overlayFg.getRed(), overlayFg.getGreen(), overlayFg.getBlue(), 
                                    (int)(overlayAlpha * 255)));
                g2.setFont(new Font("Courier New", Font.BOLD, 48));
                FontMetrics fm = g2.getFontMetrics();
                int textWidth = fm.stringWidth(overlayText);
                int textHeight = fm.getHeight();
                g2.drawString(overlayText, (getWidth() - textWidth) / 2, 
                            (getHeight() + textHeight) / 2);
                
                g2.dispose();
            }
        }
    }
    
    // Felt panel background
    static class FeltPanel extends JPanel {
        private final Color base = (NIGHT_MODE ? new Color(150, 15, 25) : new Color(8, 120, 40));
        
        FeltPanel() {
            setOpaque(true);
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (FELT_TEXTURE != null) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setPaint(FELT_TEXTURE);
                g2.fillRect(0, 0, getWidth(), getHeight());
            } else {
                g.setColor(base);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        }
    }
    
    // Create felt texture
    private static TexturePaint createFeltTexture() {
        try {
            BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            
            Color base = NIGHT_MODE ? new Color(150, 15, 25) : new Color(8, 120, 40);
            g.setColor(base);
            g.fillRect(0, 0, 32, 32);
            
            Random rand = new Random(12345); // Fixed seed for consistent texture
            for (int i = 0; i < 200; i++) {
                int x = rand.nextInt(32);
                int y = rand.nextInt(32);
                int brightness = rand.nextInt(30) - 15;
                Color c = new Color(
                    Math.max(0, Math.min(255, base.getRed() + brightness)),
                    Math.max(0, Math.min(255, base.getGreen() + brightness)),
                    Math.max(0, Math.min(255, base.getBlue() + brightness))
                );
                g.setColor(c);
                g.fillRect(x, y, 1, 1);
            }
            
            g.dispose();
            return new TexturePaint(img, new Rectangle(0, 0, 32, 32));
        } catch (Throwable t) {
            return null;
        }
    }
    
    // Game state for undo functionality
    static class GameState {
        private final Stack<Card> stockPile;
        private final Stack<Card> wastePile;
        private final Stack<Card>[] foundationPiles;
        private final Stack<Card>[] tableauPiles;
        private final int score;
        
        @SuppressWarnings("unchecked")
        GameState(Solitaire game) {
            stockPile = (Stack<Card>) game.stockPile.clone();
            wastePile = (Stack<Card>) game.wastePile.clone();
            foundationPiles = new Stack[4];
            for (int i = 0; i < 4; i++) {
                foundationPiles[i] = (Stack<Card>) game.foundationPiles[i].clone();
            }
            tableauPiles = new Stack[7];
            for (int i = 0; i < 7; i++) {
                tableauPiles[i] = (Stack<Card>) game.tableauPiles[i].clone();
            }
            score = game.currentScore;
        }
        
        void restore(Solitaire game) {
            game.stockPile.clear();
            game.stockPile.addAll(stockPile);
            game.wastePile.clear();
            game.wastePile.addAll(wastePile);
            
            for (int i = 0; i < 4; i++) {
                game.foundationPiles[i].clear();
                game.foundationPiles[i].addAll(foundationPiles[i]);
            }
            
            for (int i = 0; i < 7; i++) {
                game.tableauPiles[i].clear();
                game.tableauPiles[i].addAll(tableauPiles[i]);
            }
            
            game.currentScore = score;
        }
    }
    
    // Custom glass pane for drawing dragged cards
    class DragGlassPane extends JComponent {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            
            if (isDragging && !draggedCards.isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                // Draw semi-transparent dragged cards
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f));
                
                for (int i = 0; i < draggedCards.size(); i++) {
                    Card card = draggedCards.get(i);
                    int cardX = currentDragPosition.x;
                    int cardY = currentDragPosition.y + (i * 20); // Stack cards slightly
                    
                    drawDraggedCard(g2, card, cardX, cardY);
                }
                
                g2.dispose();
            }
        }
        
        private void drawDraggedCard(Graphics2D g2, Card card, int x, int y) {
            final int cardW = 72, cardH = 96;
            
            // Card shadow
            g2.setColor(new Color(0, 0, 0, 50));
            g2.fillRoundRect(x + 3, y + 3, cardW, cardH, 8, 8);
            
            // Card background
            g2.setColor(Color.WHITE);
            g2.fillRoundRect(x, y, cardW, cardH, 8, 8);
            g2.setColor(Color.BLACK);
            g2.drawRoundRect(x, y, cardW, cardH, 8, 8);
            
            if (card.isFaceUp()) {
                // Draw card face - clean center-only design for ghost card
                g2.setColor(card.isRed() ? Color.RED : Color.BLACK);
                
                // Center symbol (larger for face cards)
                if (card.getRank() > 10) {
                    g2.setFont(new Font("SansSerif", Font.BOLD, 36));
                } else {
                    g2.setFont(new Font("SansSerif", Font.BOLD, 24));
                }
                FontMetrics fm = g2.getFontMetrics();
                String centerText = card.getRankString();
                int textW = fm.stringWidth(centerText);
                int textH = fm.getHeight();
                g2.drawString(centerText, x + (cardW - textW)/2, y + (cardH + textH)/2 - 3);
                
                // Large suit symbol below rank
                g2.setFont(new Font("SansSerif", Font.BOLD, 20));
                fm = g2.getFontMetrics();
                String suitText = card.getSuit();
                int suitW = fm.stringWidth(suitText);
                g2.drawString(suitText, x + (cardW - suitW)/2, y + (cardH + textH)/2 + 15);
            } else {
                // Improved card back pattern - consistent with regular cards
                g2.setColor(new Color(0, 50, 150)); // Darker blue for better visibility
                g2.fillRoundRect(x + 4, y + 4, cardW - 8, cardH - 8, 6, 6);
                
                // Crosshatch pattern for better visual consistency
                g2.setColor(new Color(100, 150, 255)); // Lighter blue for pattern
                g2.setStroke(new BasicStroke(1));
                
                // Diagonal lines pattern
                for (int i = 0; i < cardW + cardH; i += 6) {
                    g2.drawLine(x + i, y + 4, x + i - cardH + 8, y + cardH - 4);
                    g2.drawLine(x + 4, y + i, x + cardW - 4, y + i - cardW + 8);
                }
                
                // Border highlight
                g2.setColor(new Color(200, 220, 255));
                g2.setStroke(new BasicStroke(2));
                g2.drawRoundRect(x + 5, y + 5, cardW - 10, cardH - 10, 4, 4);
            }
        }
    }
    
    public static void main(String[] args) {
        EventQueue.invokeLater(() -> {
            new Solitaire().setVisible(true);
        });
    }
}